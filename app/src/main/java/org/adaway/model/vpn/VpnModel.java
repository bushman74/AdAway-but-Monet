package org.adaway.model.vpn;

import static org.adaway.model.adblocking.AdBlockMethod.VPN;
import static org.adaway.model.error.HostError.ENABLE_VPN_FAIL;

import android.content.Context;
import android.util.LruCache;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostEntryDao;
import org.adaway.db.entity.HostEntry;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.adblocking.DnsRequest;
import org.adaway.model.error.HostErrorException;
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
    private final HostEntryDao hostEntryDao;
    private final LruCache<String, HostEntry> blockCache;
    /**
     * The recorded requests, by host name, in the order they were first seen.
     * Written by the VPN thread and read by the screen showing them, so every access is guarded.
     */
    private final LinkedHashMap<String, Instant> logs;
    private boolean recordingLogs;
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
        this.blockCache = new LruCache<String, HostEntry>(4 * 1024) {
            @Override
            protected HostEntry create(String key) {
                return VpnModel.this.hostEntryDao.getEntry(key);
            }
        };
        this.logs = new LinkedHashMap<>();
        this.recordingLogs = false;
        this.requestCount = 0;
        setApplied(VpnServiceControls.isRunning(context));
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
        setApplied(false);
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
            return;
        }
        this.recordingFailure = null;
        this.recordingLogs = recording;
    }

    @Override
    public String getRecordingFailure() {
        return this.recordingFailure;
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
     * @return The related host entry.
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
        return this.blockCache.get(host);
    }
}
