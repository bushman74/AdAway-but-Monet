package org.adaway.model.vpn;

import static org.adaway.model.adblocking.AdBlockMethod.VPN;
import static org.adaway.model.error.HostError.ENABLE_VPN_FAIL;

import android.content.Context;
import android.util.LruCache;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostEntryDao;
import org.adaway.db.entity.HostEntry;
import org.adaway.helper.NotificationHelper;
import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.adblocking.DnsRequest;
import org.adaway.model.error.HostErrorException;
import org.adaway.tile.DnsRecordingTileService;
import org.adaway.util.PrivateDns;
import org.adaway.vpn.VpnServiceControls;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

/**
 * This class is the model to represent VPN service configuration.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class VpnModel extends AdBlockModel {
    /**
     * The number of hosts whose entry is kept in memory.
     */
    private static final int BLOCK_CACHE_SIZE = 16 * 1024;
    /**
     * Stands in the cache for a host that is not listed, as the cache cannot hold {@code null}.
     */
    private static final HostEntry NOT_LISTED = new HostEntry();
    private final HostEntryDao hostEntryDao;
    private final LruCache<String, HostEntry> blockCache;
    /**
     * The recorded requests, by host name, in the order they were first seen.
     * Written by the VPN thread and read by the screen showing them, so every access is guarded.
     */
    private final LinkedHashMap<String, Instant> logs;
    /**
     * Whether recording was asked for. Set from the screens and the quick settings tile, and read
     * by the VPN thread.
     */
    private volatile boolean recordingLogs;
    /**
     * Why the last attempt to start recording was refused, or {@code null}.
     */
    private volatile String recordingFailure;
    private int requestCount;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public VpnModel(Context context) {
        super(context);
        AppDatabase database = AppDatabase.getInstance(context);
        this.hostEntryDao = database.hostEntryDao();
        // The hosts not listed are cached too. They are most of the hosts requested, and without
        // an entry to keep, each of their requests went to the database again.
        this.blockCache = new LruCache<String, HostEntry>(BLOCK_CACHE_SIZE) {
            @Override
            protected HostEntry create(String key) {
                HostEntry entry = VpnModel.this.hostEntryDao.getEntry(key);
                return entry == null ? NOT_LISTED : entry;
            }
        };
        this.logs = new LinkedHashMap<>();
        this.recordingLogs = false;
        this.requestCount = 0;
        setApplied(VpnServiceControls.isRunning(context));
        // Nothing is recorded yet, whatever another method or an ended process left remembered.
        NotificationHelper.clearDnsRecordingNotification(context);
        rememberRecordingState(false);
    }

    @Override
    public AdBlockMethod getMethod() {
        return VPN;
    }

    @Override
    public void apply() throws HostErrorException {
        // Clear cache
        this.blockCache.evictAll();
        // Start VPN
        boolean started = VpnServiceControls.start(this.context);
        setApplied(started);
        if (!started) {
            throw new HostErrorException(ENABLE_VPN_FAIL);
        }
        setState(R.string.status_vpn_configuration_updated);
    }

    @Override
    public void revert() {
        VpnServiceControls.stop(this.context);
        // Nothing can be recorded once the VPN is off, so the recording ends with it.
        this.recordingLogs = false;
        rememberRecordingState(false);
        setApplied(false);
    }

    /**
     * Follow the VPN service when it starts or stops by itself rather than through this model: on
     * boot, when it restarts after a failure, or when the system hands the VPN to another app.
     * The home screen follows the service directly, but the quick settings tiles only know the
     * state remembered for them.
     *
     * @param started Whether the service is now started.
     */
    public void onVpnStatusChanged(boolean started) {
        if (PreferenceHelper.getLastKnownAdBlocked(this.context) != started) {
            setApplied(started);
        }
        rememberRecordingState(this.recordingLogs && started);
    }

    /**
     * Without root, DNS requests are only seen by the VPN, so nothing is recorded while it is off.
     */
    @Override
    public boolean isRecordingLogs() {
        return this.recordingLogs && VpnServiceControls.isRunning(this.context);
    }

    /**
     * Start or stop recording. Starting is refused while the VPN is off: there would be nothing
     * to record, yet the screen showed a recording in progress.
     */
    @Override
    public void setRecordingLogs(boolean recording) {
        if (recording && !VpnServiceControls.isRunning(this.context)) {
            this.recordingFailure = this.context.getString(R.string.dns_recording_error_vpn_stopped);
            this.recordingLogs = false;
            rememberRecordingState(false);
            return;
        }
        this.recordingFailure = null;
        this.recordingLogs = recording;
        rememberRecordingState(recording);
    }

    /**
     * Remember whether requests are recorded, for the quick settings tile, and have it redrawn
     * when that changed. As with root, a notification shows while they are recorded and opens
     * the log.
     */
    private void rememberRecordingState(boolean recording) {
        if (PreferenceHelper.getLastKnownDnsRecording(this.context) == recording) {
            return;
        }
        PreferenceHelper.setLastKnownDnsRecording(this.context, recording);
        DnsRecordingTileService.requestUpdate(this.context);
        if (recording) {
            NotificationHelper.showDnsRecordingNotification(this.context);
        } else {
            NotificationHelper.clearDnsRecordingNotification(this.context);
        }
    }

    @Override
    public String getRecordingFailure() {
        return this.recordingFailure;
    }

    /**
     * Warn when Private DNS is on: the requests then go encrypted to its server, past the VPN,
     * so they are neither recorded nor blocked.
     */
    @Override
    public String getRecordingWarning() {
        if (!isRecordingLogs()) {
            return null;
        }
        String serverName = PrivateDns.getActiveServerName(this.context);
        if (serverName == null) {
            return null;
        }
        return this.context.getString(R.string.dns_recording_warning_private_dns_vpn, serverName);
    }

    /**
     * Tell whether recording can be started: only while the VPN runs.
     */
    public boolean canRecordLogs() {
        return VpnServiceControls.isRunning(this.context);
    }

    @Override
    public List<DnsRequest> getRequests() {
        synchronized (this.logs) {
            List<DnsRequest> requests = new ArrayList<>(this.logs.size());
            for (Map.Entry<String, Instant> log : this.logs.entrySet()) {
                requests.add(new DnsRequest(log.getKey(), log.getValue()));
            }
            return requests;
        }
    }

    @Override
    public void clearLogs() {
        synchronized (this.logs) {
            this.logs.clear();
        }
    }

    /**
     * Checks host entry related to an host name.
     *
     * @param host A hostname to check.
     * @return The related host entry, or {@code null} when the host is not listed.
     */
    public HostEntry getEntry(String host) {
        // Compute miss rate periodically
        this.requestCount++;
        if (this.requestCount >= 1000) {
            int hits = this.blockCache.hitCount();
            int misses = this.blockCache.missCount();
            double missRate = 100D * (hits + misses) / misses;
            Timber.d("Host cache miss rate: %s.", missRate);
            this.requestCount = 0;
        }
        // Add host to logs, keeping the time it was last requested
        if (this.recordingLogs) {
            synchronized (this.logs) {
                this.logs.put(host, Instant.now());
            }
        }
        // Check cache
        HostEntry entry = this.blockCache.get(host);
        return entry == NOT_LISTED ? null : entry;
    }
}
