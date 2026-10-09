package org.adaway.model.source;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED;
import static org.adaway.model.error.HostError.NO_CONNECTION;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.time.format.FormatStyle.MEDIUM;
import static java.util.Objects.requireNonNull;
import static org.adaway.db.entity.SourceType.FILE;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.HostCounts;
import org.adaway.db.LargeDatabaseCache;
import org.adaway.db.converter.ZonedDateTimeConverter;
import org.adaway.db.dao.HostEntryDao;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.db.dao.MetadataDao;
import org.adaway.db.entity.HostEntry;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.helper.NotificationHelper;
import org.adaway.model.error.HostErrorException;
import org.adaway.model.git.GitHostsSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

/**
 * This class is the model to represent hosts source management.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class SourceModel {
    /**
     * The time to establish a connection to a source.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    /**
     * The time allowed between two chunks of a source response.
     * A stalled download fails after this delay instead of hanging forever.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    /**
     * The time allowed for a whole update check.
     * Only applied to the header requests, as downloading a large source legitimately takes longer.
     */
    private static final Duration CHECK_CALL_TIMEOUT = Duration.ofMinutes(2);
    /**
     * How many times a source is tried before it is skipped for this run.
     */
    private static final int RETRIEVAL_ATTEMPTS = 2;
    /**
     * The pause before trying a failed source again, long enough for a brief network hiccup.
     */
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    private static final String LAST_MODIFIED_HEADER = "Last-Modified";
    private static final String IF_NONE_MATCH_HEADER = "If-None-Match";
    private static final String IF_MODIFIED_SINCE_HEADER = "If-Modified-Since";
    private static final String ENTITY_TAG_HEADER = "ETag";
    private static final String WEAK_ENTITY_TAG_PREFIX = "W/";
    /**
     * The application context.
     */
    private final Context context;
    /**
     * The application database.
     */
    private final AppDatabase database;
    /**
     * The {@link HostsSource} DAO.
     */
    private final HostsSourceDao hostsSourceDao;
    /**
     * The {@link HostListItem} DAO.
     */
    private final HostListItemDao hostListItemDao;
    /**
     * The {@link HostEntry} DAO.
     */
    private final HostEntryDao hostEntryDao;
    /**
     * The metadata DAO.
     */
    private final MetadataDao metadataDao;
    /**
     * The update available status.
     */
    private final MutableLiveData<Boolean> updateAvailable;
    /**
     * The model state.
     */
    private final MutableLiveData<String> state;
    /**
     * The HTTP client to download hosts sources ({@code null} until initialized by {@link #getHttpClient()}).
     */
    private OkHttpClient cachedHttpClient;
    /**
     * The HTTP client to check hosts sources, bounded by an overall call timeout
     * ({@code null} until initialized by {@link #getCheckHttpClient()}).
     */
    private OkHttpClient cachedCheckHttpClient;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public SourceModel(Context context) {
        this.context = context;
        this.database = AppDatabase.getInstance(this.context);
        AppDatabase database = this.database;
        this.hostsSourceDao = database.hostsSourceDao();
        this.hostListItemDao = database.hostsListItemDao();
        this.hostEntryDao = database.hostEntryDao();
        this.metadataDao = database.metadataDao();
        this.state = new MutableLiveData<>("");
        this.updateAvailable = new MutableLiveData<>();
        this.updateAvailable.setValue(false);
    }

    /**
     * Get the model state.
     *
     * @return The model state.
     */
    public LiveData<String> getState() {
        return this.state;
    }

    /**
     * Get the update available status.
     *
     * @return {@code true} if source update is available, {@code false} otherwise.
     */
    public LiveData<Boolean> isUpdateAvailable() {
        return this.updateAvailable;
    }

    /**
     * Check the enabled hosts sources for update.
     *
     * @param listener The listener notified as each source is checked.
     * @param budget   What the run is allowed to spend before giving up.
     * @return What the check found, for the retrieval to work from.
     * @throws HostErrorException If the device has no connection.
     */
    public SourceUpdatePlan checkForUpdate(SourceUpdateListener listener, UpdateBudget budget)
            throws HostErrorException {
        // Check current connection
        if (isDeviceOffline()) {
            throw new HostErrorException(NO_CONNECTION);
        }
        List<HostsSource> outdatedSources = new ArrayList<>();
        Map<Integer, ZonedDateTime> onlineModificationDates = new HashMap<>();
        // Get enabled hosts sources
        List<HostsSource> sources = this.hostsSourceDao.getEnabled();
        if (sources.isEmpty()) {
            // Return no update as no source
            this.updateAvailable.postValue(false);
            return new SourceUpdatePlan(outdatedSources, onlineModificationDates);
        }
        // Update state
        setState(R.string.status_check);
        // Check each source
        ZonedDateTime now = ZonedDateTime.now();
        for (int index = 0; index < sources.size(); index++) {
            HostsSource source = sources.get(index);
            if (budget.timedOut()) {
                // The sources left unchecked are left for the next run, rather than failing the
                // whole update over them.
                Timber.w("Stopping the check: the run has spent its time.");
                break;
            }
            // Get URL and lastModified from db
            ZonedDateTime lastModifiedLocal = source.getLocalModificationDate();
            // Update state
            setState(R.string.status_check_source, source.getLabel());
            listener.onSourceUpdateStarted(index, sources.size(), source.getLabel(), false);
            // Get hosts source last update
            ZonedDateTime lastModifiedOnline = getHostsSourceLastUpdate(source);
            // Some help with debug here
            Timber.d("lastModifiedLocal: %s", dateToString(lastModifiedLocal));
            Timber.d("lastModifiedOnline: %s", dateToString(lastModifiedOnline));
            // The date is unknown both for a source reporting none and for one that could not be
            // reached. Keep what was known rather than forgetting it over a connection that
            // happened to fail, and decide on the best date available.
            ZonedDateTime knownModifiedOnline = lastModifiedOnline;
            if (lastModifiedOnline == null) {
                knownModifiedOnline = source.getOnlineModificationDate();
            } else {
                this.hostsSourceDao.updateOnlineModificationDate(source.getId(), lastModifiedOnline);
            }
            if (knownModifiedOnline != null) {
                onlineModificationDates.put(source.getId(), knownModifiedOnline);
            }
            // Classify the source the same way the retrieval does, so what is reported as
            // outdated is exactly what pressing update acts on.
            if (SourceUpdateStatus.needsRetrieval(lastModifiedLocal, knownModifiedOnline, now)) {
                outdatedSources.add(source);
            }
        }
        boolean updateAvailable = !outdatedSources.isEmpty();
        // Update statuses
        Timber.d("Update check result: %s.", updateAvailable);
        if (updateAvailable) {
            setState(R.string.status_update_available);
        } else {
            setState(R.string.status_no_update_found);
        }
        this.updateAvailable.postValue(updateAvailable);
        return new SourceUpdatePlan(outdatedSources, onlineModificationDates);
    }

    /**
     * Format {@link ZonedDateTime} for printing.
     *
     * @param zonedDateTime The date to format.
     * @return The formatted date string.
     */
    private String dateToString(ZonedDateTime zonedDateTime) {
        if (zonedDateTime == null) {
            return "not defined";
        } else {
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(MEDIUM);
            return zonedDateTime + " (" + zonedDateTime.format(dateTimeFormatter) + ")";
        }
    }

    /**
     * Checks if device is offline.
     *
     * @return returns {@code true} if device is offline, {@code false} otherwise.
     */
    private boolean isDeviceOffline() {
        ConnectivityManager connectivityManager = (ConnectivityManager) this.context.getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return true;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        // Only the presence of an internet capable network is required. Demanding a validated one
        // reports a captive portal, or a network still being validated, as no connection at all.
        return capabilities == null || !capabilities.hasCapability(NET_CAPABILITY_INTERNET);
    }

    /**
     * Get the hosts source last online update.
     *
     * @param source The hosts source to get last online update.
     * @return The last online date, {@code null} if the date could not be retrieved.
     */
    @Nullable
    private ZonedDateTime getHostsSourceLastUpdate(HostsSource source) {
        switch (source.getType()) {
            case URL:
                return getUrlLastUpdate(source);
            case FILE:
                Uri fileUri = Uri.parse(source.getUrl());
                return getFileLastUpdate(fileUri);
            default:
                return null;
        }
    }

    /**
     * Get the url last online update.
     *
     * @param source The source to get last online update.
     * @return The last online date, {@code null} if the date could not be retrieved.
     */
    private ZonedDateTime getUrlLastUpdate(HostsSource source) {
        String url = source.getUrl();
        Timber.v("Checking url last update for source: %s.", url);
        // Check Git hosting
        if (GitHostsSource.isHostedOnGit(url)) {
            try {
                return GitHostsSource.getSource(url).getLastUpdate();
            } catch (MalformedURLException e) {
                Timber.w(e, "Failed to get Git last commit for url %s.", url);
                return null;
            }
        }
        // Default hosting
        Request request = getRequestFor(source).head().build();
        try (Response response = getCheckHttpClient().newCall(request).execute()) {
            String lastModified = response.header(LAST_MODIFIED_HEADER);
            if (lastModified == null) {
                return response.code() == HTTP_NOT_MODIFIED ?
                     source.getOnlineModificationDate() : null;
            }
            return ZonedDateTime.parse(lastModified, RFC_1123_DATE_TIME);
        } catch (IOException | DateTimeParseException e) {
            Timber.e(e, "Exception while fetching last modified date of source %s.", url);
            return null;
        }
    }

    /**
     * Get the file last modified date.
     *
     * @param fileUri The file uri to get last modified date.
     * @return The file last modified date, {@code null} if date could not be retrieved.
     */
    private ZonedDateTime getFileLastUpdate(Uri fileUri) {
        ContentResolver contentResolver = this.context.getContentResolver();
        try (Cursor cursor = contentResolver.query(fileUri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                Timber.w("The content resolver could not find %s.", fileUri);
                return null;
            }
            int columnIndex = cursor.getColumnIndex(COLUMN_LAST_MODIFIED);
            if (columnIndex == -1) {
                Timber.w("The content resolver does not support last modified column %s.", fileUri);
                return null;
            }
            return ZonedDateTimeConverter.fromTimestamp(cursor.getLong(columnIndex));
        } catch (SecurityException e) {
            Timber.i(e, "The SAF permission was removed.");
            return null;
        }
    }

    /**
     * A listener notified before each source is checked or retrieved.
     */
    public interface SourceUpdateListener {
        /**
         * Called before a source is checked or retrieved.
         *
         * @param completed  The number of sources already dealt with in this phase.
         * @param total      The number of sources the phase deals with.
         * @param label      The label of the source about to be dealt with.
         * @param retrieving {@code true} while retrieving the sources, {@code false} while
         *                   checking them.
         */
        void onSourceUpdateStarted(int completed, int total, String label, boolean retrieving);
    }

    /**
     * Check the hosts sources and retrieve the outdated ones.
     *
     * @throws HostErrorException If the device has no connection.
     */
    public void retrieveHostsSources() throws HostErrorException {
        retrieveHostsSources((completed, total, label, retrieving) -> {
        }, new UpdateBudget());
    }

    /**
     * Check the hosts sources and retrieve the outdated ones.
     *
     * @param listener The listener notified as each source is checked, then retrieved.
     * @param budget   What the run is allowed to spend before giving up.
     * @throws HostErrorException If the device has no connection.
     */
    public void retrieveHostsSources(SourceUpdateListener listener, UpdateBudget budget)
            throws HostErrorException {
        SourceUpdatePlan plan = checkForUpdate(listener, budget);
        retrieveHostsSources(plan, listener, budget);
    }

    /**
     * Retrieve the sources a check found outdated.
     *
     * A source that fails is tried once more, then skipped, and the reason is recorded with it.
     * One source being unreachable no longer stops the update of the others: the sources that
     * could not be updated are listed in a notification instead.
     *
     * @param plan     What the check of this run found.
     * @param listener The listener notified as each source is retrieved.
     * @param budget   What the run is allowed to spend before giving up.
     * @throws HostErrorException If the device has no connection.
     */
    public void retrieveHostsSources(
            SourceUpdatePlan plan,
            SourceUpdateListener listener,
            UpdateBudget budget
    ) throws HostErrorException {
        // Check connection status
        if (isDeviceOffline()) {
            throw new HostErrorException(NO_CONNECTION);
        }
        // Update state to downloading
        setState(R.string.status_retrieve);
        LargeDatabaseCache.acquire(this.database);
        try {
            retrieveOutdatedSources(plan, listener, budget);
        } finally {
            LargeDatabaseCache.release(this.database);
        }
        // Mark no update available
        this.updateAvailable.postValue(false);
    }

    /**
     * Retrieve the sources a check found outdated, then rebuild the hosts to block.
     */
    private void retrieveOutdatedSources(
            SourceUpdatePlan plan,
            SourceUpdateListener listener,
            UpdateBudget budget
    ) {
        // Clear the disabled sources
        for (HostsSource source : this.hostsSourceDao.getAll()) {
            if (!source.isEnabled()) {
                this.hostListItemDao.clearSourceHosts(source.getId());
                this.hostsSourceDao.clearProperties(source.getId());
            }
        }
        // Compute current date in UTC timezone
        ZonedDateTime now = ZonedDateTime.now();
        List<HostsSource> outdatedSources = plan.getOutdatedSources();
        List<String> failedSources = new ArrayList<>();
        for (int index = 0; index < outdatedSources.size(); index++) {
            HostsSource source = outdatedSources.get(index);
            int sourceId = source.getId();
            if (budget.exhausted()) {
                // The run stops here, having spent its time or failed too often in a row. The
                // sources it did not reach are reported as interrupted rather than left silent.
                Timber.w("Giving up retrieving the sources: the run has spent its budget.");
                for (HostsSource skipped : outdatedSources.subList(index, outdatedSources.size())) {
                    this.hostsSourceDao.updateLastUpdateError(skipped.getId(), SourceFailure.ABORTED);
                    failedSources.add(skipped.getLabel());
                }
                break;
            }
            listener.onSourceUpdateStarted(index, outdatedSources.size(), source.getLabel(), true);
            String failure = retrieveWithRetry(source, budget);
            if (failure != null) {
                Timber.w("Skipping host source %s: %s.", source.getUrl(), failure);
                this.hostsSourceDao.updateLastUpdateError(sourceId, failure);
                failedSources.add(source.getLabel());
                budget.recordFailure();
                continue;
            }
            // Unknown for a source that could not be checked, which is retrieved anyway once it
            // goes stale; its local date then stands in as the date of the copy on the device.
            ZonedDateTime onlineModificationDate = plan.getOnlineModificationDates().get(sourceId);
            // Update local and online modification dates to now
            ZonedDateTime localModificationDate =
                    onlineModificationDate != null && onlineModificationDate.isAfter(now)
                            ? onlineModificationDate
                            : now;
            this.hostsSourceDao.updateModificationDates(sourceId, localModificationDate, onlineModificationDate);
            // Update size
            this.hostsSourceDao.updateSize(sourceId);
            this.hostsSourceDao.updateLastUpdateError(sourceId, null);
            budget.recordSuccess();
        }
        if (failedSources.isEmpty()) {
            NotificationHelper.clearSourceUpdateFailureNotification(this.context);
        } else {
            NotificationHelper.showSourceUpdateFailureNotification(this.context, failedSources);
        }
        // Synchronize hosts entries
        syncHostEntries();
    }

    /**
     * Retrieve a source, trying a second time if the first attempt fails.
     *
     * @param source The source to retrieve.
     * @param budget What the run is allowed to spend; no second attempt is made once it is spent.
     * @return Why the source could not be retrieved, or {@code null} once it was.
     */
    @Nullable
    private String retrieveWithRetry(HostsSource source, UpdateBudget budget) {
        String failure = null;
        for (int attempt = 1; attempt <= RETRIEVAL_ATTEMPTS; attempt++) {
            try {
                retrieveSource(source);
                return null;
            } catch (IOException | SecurityException e) {
                Timber.w(e, "Attempt %d to retrieve host source %s failed.", attempt, source.getUrl());
                failure = SourceFailure.of(e, source.getType() == FILE);
                if (attempt < RETRIEVAL_ATTEMPTS && !budget.timedOut()) {
                    SystemClock.sleep(RETRY_DELAY.toMillis());
                } else {
                    break;
                }
            }
        }
        return failure;
    }

    /**
     * Retrieve a source once, from the network or from a file.
     *
     * @param source The source to retrieve.
     * @throws IOException If the source could not be retrieved.
     */
    private void retrieveSource(HostsSource source) throws IOException {
        switch (source.getType()) {
            case URL:
                downloadHostSource(source);
                break;
            case FILE:
                readSourceFile(source);
                break;
            default:
                Timber.w("Hosts source type  is not supported.");
        }
    }

    /**
     * Synchronize hosts entries from current source states.
     */
    public void syncHostEntries() {
        setState(R.string.status_sync_database);
        LargeDatabaseCache.acquire(this.database);
        try {
            // Run the whole rebuild as a single transaction, otherwise every statement below pays
            // for its own commit which dominates the cost on large host lists.
            this.database.runInTransaction(() -> {
                int blockedCount = this.hostEntryDao.sync();
                // Recorded in the same transaction as the rebuild it describes, so the generated
                // hosts file can never be considered current for entries it was not built from.
                this.metadataDao.markHostEntriesRebuilt();
                // The counters shown on the home screen, stored with the rebuild they describe.
                // The rebuild already counted the blocked hosts, the costly ones to count.
                HostCounts.storeRebuilt(this.database, blockedCount);
            });
        } finally {
            LargeDatabaseCache.release(this.database);
        }
    }

    /**
     * Get the HTTP client to download hosts sources.
     *
     * @return The HTTP client to download hosts sources.
     */
    @NonNull
    private OkHttpClient getHttpClient() {
        if (this.cachedHttpClient == null) {
            // No disk cache: every download carries the conditions of the copy already loaded,
            // so a cached response was never read back, and caching wrote each downloaded source
            // to the storage a second time.
            this.cachedHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .readTimeout(READ_TIMEOUT)
                    .build();
        }
        return this.cachedHttpClient;
    }

    /**
     * Get the HTTP client to check hosts sources for update.
     * It shares the connection pool of {@link #getHttpClient()} and adds an overall call timeout,
     * which is safe here because these requests carry no body.
     *
     * @return The HTTP client to check hosts sources.
     */
    @NonNull
    private OkHttpClient getCheckHttpClient() {
        if (this.cachedCheckHttpClient == null) {
            this.cachedCheckHttpClient = getHttpClient().newBuilder()
                    .callTimeout(CHECK_CALL_TIMEOUT)
                    .build();
        }
        return this.cachedCheckHttpClient;
    }

    /**
     * Get request builder for an hosts source.
     * All cache data available are filled into the headers.
     *
     * @param source The hosts source to get request builder.
     * @return The hosts source request builder.
     */
    private Request.Builder getRequestFor(HostsSource source) {
        Request.Builder request = new Request.Builder().url(source.getUrl());
        if (source.getEntityTag() != null) {
            request = request.header(IF_NONE_MATCH_HEADER, source.getEntityTag());
        }
        if (source.getOnlineModificationDate() != null) {
            String lastModified = source.getOnlineModificationDate().format(RFC_1123_DATE_TIME);
            request = request.header(IF_MODIFIED_SINCE_HEADER, lastModified);
        }
        return request;
    }

    /**
     * Download an hosts source file and append it to the database.
     *
     * @param source The hosts source to download.
     * @throws IOException If the hosts source could not be downloaded.
     */
    private void downloadHostSource(HostsSource source) throws IOException {
        // Get hosts file URL
        String hostsFileUrl = source.getUrl();
        Timber.v("Downloading hosts file: %s.", hostsFileUrl);
        // Set state to downloading hosts source
        setState(R.string.status_download_source, source.getLabel());
        // Create request
        Request request = getRequestFor(source).build();
        // Request hosts file and open byte stream
        try (Response response = getHttpClient().newCall(request).execute();
             Reader reader = requireNonNull(response.body()).charStream();
             BufferedReader bufferedReader = new BufferedReader(reader)) {
            // Skip source parsing if not modified
            if (response.code() == HTTP_NOT_MODIFIED) {
                Timber.d("Source %s was not updated since last fetch.", source.getUrl());
                return;
            }
            // An error page is not a hosts list. It used to be parsed as one, which replaced the
            // source's hosts with whatever the page happened to contain, usually nothing.
            if (!response.isSuccessful()) {
                throw new SourceHttpException(response.code());
            }
            // Parse source
            parseSourceInputStream(source, bufferedReader);
            // Extract ETag if present. Stored only once the source was read whole: a tag stored
            // before a failed read would make the server answer "not modified" next time, and the
            // source would keep its previous hosts until it changed again.
            String entityTag = response.header(ENTITY_TAG_HEADER);
            if (entityTag != null) {
                if (entityTag.startsWith(WEAK_ENTITY_TAG_PREFIX)) {
                    entityTag = entityTag.substring(WEAK_ENTITY_TAG_PREFIX.length());
                }
                this.hostsSourceDao.updateEntityTag(source.getId(), entityTag);
            }
        } catch (IOException e) {
            throw new IOException("Exception while downloading hosts file from " + hostsFileUrl + ".", e);
        }
    }

    /**
     * Read a hosts source file and append it to the database.
     *
     * @param hostsSource The hosts source to copy.
     * @throws IOException If the hosts source could not be copied.
     */
    private void readSourceFile(HostsSource hostsSource) throws IOException {
        // Get hosts file URI
        String hostsFileUrl = hostsSource.getUrl();
        Uri fileUri = Uri.parse(hostsFileUrl);
        Timber.v("Reading hosts source file: %s.", hostsFileUrl);
        // Set state to copying hosts source
        setState(R.string.status_read_source, hostsSource.getLabel());
        try (InputStream inputStream = this.context.getContentResolver().openInputStream(fileUri);
             InputStreamReader reader = new InputStreamReader(inputStream);
             BufferedReader bufferedReader = new BufferedReader(reader)) {
            parseSourceInputStream(hostsSource, bufferedReader);
        } catch (IOException e) {
            throw new IOException("Error while reading hosts file from " + hostsFileUrl + ".", e);
        }
    }

    /**
     * Parse a source from its input stream to store it into database.
     *
     * @param hostsSource The host source to parse.
     * @param reader      The host source reader.
     * @throws IOException If the source could not be read to its end. Its previous hosts are kept.
     */
    private void parseSourceInputStream(HostsSource hostsSource, BufferedReader reader)
            throws IOException {
        setState(R.string.status_parse_source, hostsSource.getLabel());
        long startTime = System.currentTimeMillis();
        new SourceLoader(hostsSource).parse(reader, this.database);
        long endTime = System.currentTimeMillis();
        Timber.i("Parsed " + hostsSource.getUrl() + " in " + (endTime - startTime) / 1000 + "s");
    }

    /**
     * Enable all hosts sources.
     *
     * @return {@code true} if at least one source was updated, {@code false} otherwise.
     */
    public boolean enableAllSources() {
        boolean updated = false;
        for (HostsSource source : this.hostsSourceDao.getAll()) {
            if (!source.isEnabled()) {
                this.hostsSourceDao.toggleEnabled(source);
                updated = true;
            }
        }
        return updated;
    }

    private void setState(@StringRes int stateResId, Object... details) {
        String state = this.context.getString(stateResId, details);
        Timber.d("Source model state: %s.", state);
        this.state.postValue(state);
    }
}
