package org.adaway.model.source;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED;
import static org.adaway.model.error.HostError.DOWNLOAD_FAILED;
import static org.adaway.model.error.HostError.NO_CONNECTION;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.time.format.FormatStyle.MEDIUM;
import static java.util.Objects.requireNonNull;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.HostCounts;
import org.adaway.db.converter.ZonedDateTimeConverter;
import org.adaway.db.dao.HostEntryDao;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.db.dao.MetadataDao;
import org.adaway.db.entity.HostEntry;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
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

import okhttp3.Cache;
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
     * The HTTP client cache size (100Mo).
     */
    private static final long CACHE_SIZE = 100L * 1024L * 1024L;
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
     * Check if there is update available for hosts sources.
     *
     * @throws HostErrorException If the hosts sources could not be checked.
     */
    public boolean checkForUpdate() throws HostErrorException {
        return checkForUpdate((completed, total, label) -> {
        });
    }

    /**
     * Check if there is update available for hosts sources.
     *
     * @param listener The listener notified of the check progress.
     * @throws HostErrorException If the hosts sources could not be checked.
     */
    public boolean checkForUpdate(SourceUpdateListener listener) throws HostErrorException {
        return checkForUpdate(listener, new UpdateBudget());
    }

    /**
     * Check if there is update available for hosts sources.
     *
     * @param listener The listener notified of the check progress.
     * @param budget   What the run is allowed to spend before giving up.
     * @throws HostErrorException If the hosts sources could not be checked.
     */
    public boolean checkForUpdate(SourceUpdateListener listener, UpdateBudget budget)
            throws HostErrorException {
        // Check current connection
        if (isDeviceOffline()) {
            throw new HostErrorException(NO_CONNECTION);
        }
        // Initialize update status
        boolean updateAvailable = false;
        // Get enabled hosts sources
        List<HostsSource> sources = this.hostsSourceDao.getEnabled();
        if (sources.isEmpty()) {
            // Return no update as no source
            this.updateAvailable.postValue(false);
            return false;
        }
        // Update state
        setState(R.string.status_check);
        // Check each source
        ZonedDateTime now = ZonedDateTime.now();
        int checkedSourceCount = 0;
        for (HostsSource source : sources) {
            if (budget.exhausted()) {
                Timber.w("Giving up checking the sources: the run has spent its budget.");
                throw new HostErrorException(DOWNLOAD_FAILED);
            }
            // Get URL and lastModified from db
            ZonedDateTime lastModifiedLocal = source.getLocalModificationDate();
            // Update state
            setState(R.string.status_check_source, source.getLabel());
            listener.onSourceUpdateStarted(checkedSourceCount, sources.size(), source.getLabel());
            checkedSourceCount++;
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
            // Classify the source the same way the retrieval does, so what is reported as
            // outdated is exactly what pressing update acts on.
            if (SourceUpdateStatus.needsRetrieval(lastModifiedLocal, knownModifiedOnline, now)) {
                updateAvailable = true;
            }
        }
        // Update statuses
        Timber.d("Update check result: %s.", updateAvailable);
        if (updateAvailable) {
            setState(R.string.status_update_available);
        } else {
            setState(R.string.status_no_update_found);
        }
        this.updateAvailable.postValue(updateAvailable);
        return updateAvailable;
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
     * A listener notified before each enabled source is retrieved.
     */
    public interface SourceUpdateListener {
        /**
         * Called before a source is retrieved.
         *
         * @param completed The number of sources already retrieved.
         * @param total     The total number of enabled sources to retrieve.
         * @param label     The label of the source about to be retrieved.
         */
        void onSourceUpdateStarted(int completed, int total, String label);
    }

    /**
     * Retrieve all hosts sources files to copy into a private local file.
     *
     * @throws HostErrorException If the hosts sources could not be downloaded.
     */
    public void retrieveHostsSources() throws HostErrorException {
        retrieveHostsSources((completed, total, label) -> {
        });
    }

    /**
     * Retrieve all hosts sources files to copy into a private local file.
     *
     * @param listener The listener notified of the retrieval progress.
     * @throws HostErrorException If the hosts sources could not be downloaded.
     */
    public void retrieveHostsSources(SourceUpdateListener listener) throws HostErrorException {
        retrieveHostsSources(listener, new UpdateBudget());
    }

    /**
     * Retrieve all hosts sources files to copy into a private local file.
     *
     * @param listener The listener notified of the retrieval progress.
     * @param budget   What the run is allowed to spend before giving up.
     * @throws HostErrorException If the hosts sources could not be downloaded.
     */
    public void retrieveHostsSources(SourceUpdateListener listener, UpdateBudget budget)
            throws HostErrorException {
        // Check connection status
        if (isDeviceOffline()) {
            throw new HostErrorException(NO_CONNECTION);
        }
        // Update state to downloading
        setState(R.string.status_retrieve);
        // Initialize copy counters
        int numberOfCopies = 0;
        int numberOfFailedCopies = 0;
        // Compute current date in UTC timezone
        ZonedDateTime now = ZonedDateTime.now();
        List<HostsSource> allSources = this.hostsSourceDao.getAll();
        // First pass: clear the disabled sources and work out which of the remaining ones are
        // outdated. Their online date is fetched once here and reused below, so the progress
        // reported afterwards counts only the sources that are actually going to be retrieved.
        List<HostsSource> outdatedSources = new ArrayList<>();
        Map<Integer, ZonedDateTime> onlineModificationDates = new HashMap<>();
        for (HostsSource source : allSources) {
            int sourceId = source.getId();
            if (!source.isEnabled()) {
                this.hostListItemDao.clearSourceHosts(sourceId);
                this.hostsSourceDao.clearProperties(sourceId);
                continue;
            }
            if (budget.exhausted()) {
                Timber.w("Giving up checking the sources: the run has spent its budget.");
                break;
            }
            setState(R.string.status_check_source, source.getLabel());
            ZonedDateTime onlineModificationDate = getHostsSourceLastUpdate(source);
            if (onlineModificationDate == null) {
                // Unknown, either because the source reports no date or because it could not be
                // reached. Standing in the current time here would mark every source outdated
                // whenever the connection fails, and download the lot. The two cannot be told
                // apart, so this does not count as a failure either: only a download that throws
                // says for certain that the connection is unusable.
                onlineModificationDate = source.getOnlineModificationDate();
            }
            if (onlineModificationDate != null) {
                onlineModificationDates.put(sourceId, onlineModificationDate);
            }
            ZonedDateTime localModificationDate = source.getLocalModificationDate();
            if (!SourceUpdateStatus.needsRetrieval(localModificationDate, onlineModificationDate, now)) {
                Timber.i("Skip source %s: no update.", source.getLabel());
                continue;
            }
            outdatedSources.add(source);
        }
        int outdatedSourceCount = outdatedSources.size();
        int completedSourceCount = 0;
        // Second pass: retrieve the outdated sources
        for (HostsSource source : outdatedSources) {
            if (budget.exhausted()) {
                Timber.w("Giving up retrieving the sources: the run has spent its budget.");
                break;
            }
            int sourceId = source.getId();
            // Unknown for a source that could not be checked, which is retrieved anyway once it
            // goes stale; its local date then stands in as the date of the copy on the device.
            ZonedDateTime onlineModificationDate = onlineModificationDates.get(sourceId);
            listener.onSourceUpdateStarted(completedSourceCount, outdatedSourceCount, source.getLabel());
            completedSourceCount++;
            // Increment number of copy
            numberOfCopies++;
            try {
                // Check hosts source type
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
                // Update local and online modification dates to now
                ZonedDateTime localModificationDate =
                        onlineModificationDate != null && onlineModificationDate.isAfter(now)
                                ? onlineModificationDate
                                : now;
                this.hostsSourceDao.updateModificationDates(sourceId, localModificationDate, onlineModificationDate);
                // Update size
                this.hostsSourceDao.updateSize(sourceId);
                budget.recordSuccess();
            } catch (IOException e) {
                Timber.w(e, "Failed to retrieve host source %s.", source.getUrl());
                // Increment number of failed copy
                numberOfFailedCopies++;
                budget.recordFailure();
            }
        }
        // Check if all copies failed
        if (numberOfCopies == numberOfFailedCopies && numberOfCopies != 0) {
            throw new HostErrorException(DOWNLOAD_FAILED);
        }
        // Synchronize hosts entries
        syncHostEntries();
        // Mark no update available
        this.updateAvailable.postValue(false);
    }

    /**
     * Synchronize hosts entries from current source states.
     */
    public void syncHostEntries() {
        setState(R.string.status_sync_database);
        // Run the whole rebuild as a single transaction, otherwise every statement below pays
        // for its own commit which dominates the cost on large host lists.
        this.database.runInTransaction(() -> {
            this.hostEntryDao.sync();
            // Recorded in the same transaction as the rebuild it describes, so the generated hosts
            // file can never be considered current for entries it was not built from.
            this.metadataDao.markHostEntriesRebuilt();
        });
        // Refresh the cached counters here, once, rather than counting distinct hosts across
        // millions of rows every time the home screen is shown. Done outside the transaction
        // because it only reads.
        HostCounts.refresh(this.database);
    }

    /**
     * Get the HTTP client to download hosts sources.
     *
     * @return The HTTP client to download hosts sources.
     */
    @NonNull
    private OkHttpClient getHttpClient() {
        if (this.cachedHttpClient == null) {
            this.cachedHttpClient = new OkHttpClient.Builder()
                    .cache(new Cache(this.context.getCacheDir(), CACHE_SIZE))
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
            // Extract ETag if present
            String entityTag = response.header(ENTITY_TAG_HEADER);
            if (entityTag != null) {
                if (entityTag.startsWith(WEAK_ENTITY_TAG_PREFIX)) {
                    entityTag = entityTag.substring(WEAK_ENTITY_TAG_PREFIX.length());
                }
                this.hostsSourceDao.updateEntityTag(source.getId(), entityTag);
            }
            // Parse source
            parseSourceInputStream(source, bufferedReader);
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
     */
    private void parseSourceInputStream(HostsSource hostsSource, BufferedReader reader) {
        setState(R.string.status_parse_source, hostsSource.getLabel());
        long startTime = System.currentTimeMillis();
        new SourceLoader(hostsSource).parse(reader, this.database, this.hostListItemDao);
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
