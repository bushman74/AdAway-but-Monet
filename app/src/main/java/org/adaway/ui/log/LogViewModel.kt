package org.adaway.ui.log

import android.app.Application
import androidx.annotation.StringRes
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.AdAwayApplication
import org.adaway.R
import org.adaway.db.AppDatabase
import org.adaway.db.dao.HostEntryDao
import org.adaway.db.dao.HostListItemDao
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.HostsSource.USER_SOURCE_ID
import org.adaway.db.entity.ListType
import org.adaway.model.adblocking.AdBlockMethod
import org.adaway.model.adblocking.AdBlockModel
import org.adaway.model.vpn.VpnModel
import org.adaway.vpn.VpnStatusRepository
import org.adaway.util.ExpressiveToast
import timber.log.Timber

/**
 * A message about the last recording attempt: why it failed, or a limitation of the running one.
 */
data class RecordingMessage(@StringRes val titleRes: Int, val text: String)

class LogViewModel(application: Application) : AndroidViewModel(application) {
    private val adBlockModel: AdBlockModel = (application as AdAwayApplication).adBlockModel
    private val hostListItemDao: HostListItemDao = AppDatabase.getInstance(application).hostsListItemDao()
    private val hostEntryDao: HostEntryDao = AppDatabase.getInstance(application).hostEntryDao()
    private val _sort = MutableStateFlow(LogEntrySort.TOP_LEVEL_DOMAIN)
    val sort: StateFlow<LogEntrySort> = _sort

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /**
     * The requests to show: everything recorded, narrowed down to what the search matches.
     * Filtering runs off the main thread because a long recording holds thousands of requests.
     */
    val visibleLogs: StateFlow<List<LogEntry>> = combine(_logs, _searchQuery) { logs, query ->
        val trimmed = query.trim()
        if (trimmed.isEmpty()) logs else logs.filter { it.host.contains(trimmed, ignoreCase = true) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    /**
     * Whether recording can be started. Without root, DNS requests are only seen by the VPN, so
     * nothing can be recorded while it is off.
     */
    private val _recordingAvailable = MutableStateFlow(true)
    val recordingAvailable: StateFlow<Boolean> = _recordingAvailable

    /**
     * Whether the recording is being started or stopped right now.
     *
     * Starting a capture goes through a privileged shell and waits for the capture to prove it is
     * alive, which takes long enough to look like nothing happened.
     */
    private val _togglingRecording = MutableStateFlow(false)
    val togglingRecording: StateFlow<Boolean> = _togglingRecording

    private val _recordingMessage = MutableStateFlow<RecordingMessage?>(null)
    val recordingMessage: StateFlow<RecordingMessage?> = _recordingMessage

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * Whether the recorded requests have been read at least once.
     * Until they have, the screen says nothing about an empty list, since it does not yet know
     * whether it is empty.
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    init {
        // Read again whenever the VPN starts or stops, so the screen follows it while open.
        viewModelScope.launch {
            VpnStatusRepository.status.collect { refreshRecordingState() }
        }
    }

    /**
     * Read the recording state back from the ad block model.
     * It queries the running processes through a privileged shell, so it never runs on the main
     * thread.
     */
    fun refreshRecordingState() {
        viewModelScope.launch {
            val model = adBlockModel
            withContext(Dispatchers.IO) {
                _recording.value = model.isRecordingLogs
                _recordingAvailable.value = model !is VpnModel || model.canRecordLogs()
            }
        }
    }

    fun areBlockedRequestsIgnored(): Boolean = adBlockModel.method == AdBlockMethod.ROOT

    private fun buildRecordingMessage(): RecordingMessage? {
        if (!_recording.value) {
            val failure = adBlockModel.recordingFailure ?: return null
            return RecordingMessage(R.string.dns_recording_error_title, failure)
        }
        val warning = adBlockModel.recordingWarning ?: return null
        return RecordingMessage(R.string.dns_recording_warning_title, warning)
    }

    fun dismissRecordingMessage() {
        _recordingMessage.value = null
    }

    fun clearLogs() {
        adBlockModel.clearLogs()
        _logs.value = emptyList()
        _loaded.value = true
        _refreshing.value = false
    }

    fun search(query: String) {
        _searchQuery.value = query
    }

    fun updateLogs() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val logItems = withContext(Dispatchers.IO) {
                    // Kotlin's collection functions, not Java streams: Stream.toList() only exists
                    // from Android 14 and crashed the screen on older versions.
                    adBlockModel.requests
                        .map { request ->
                            LogEntry(
                                request.host,
                                hostEntryDao.getTypeOfHost(request.host),
                                request.lastSeen
                            )
                        }
                        .sortedWith(_sort.value.comparator())
                }
                _logs.value = logItems
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Reading the captured requests must never bring the application down: it runs on
                // every resume of the screen, whether or not the capture is still running.
                Timber.w(exception, "Failed to read the captured DNS requests.")
            } finally {
                _loaded.value = true
                _refreshing.value = false
            }
        }
    }

    fun toggleSort() {
        sortDnsRequests(
            if (_sort.value == LogEntrySort.ALPHABETICAL) {
                LogEntrySort.TOP_LEVEL_DOMAIN
            } else {
                LogEntrySort.ALPHABETICAL
            }
        )
    }

    fun toggleRecording() {
        if (_togglingRecording.value) {
            return
        }
        viewModelScope.launch {
            val enable = !_recording.value
            _togglingRecording.value = true
            try {
                // Report the state the capture actually ended in rather than the requested one, so
                // a capture that fails to start does not leave the control showing as enabled.
                _recording.value = withContext(Dispatchers.IO) {
                    adBlockModel.setRecordingLogs(enable)
                    adBlockModel.isRecordingLogs
                }
                _recordingMessage.value = buildRecordingMessage()
                refreshRecordingState()
            } finally {
                _togglingRecording.value = false
            }
        }
    }

    fun addListItem(host: String, type: ListType, redirection: String?) {
        val item = HostListItem().apply {
            this.type = type
            this.host = host
            this.redirection = redirection
            isEnabled = true
            sourceId = USER_SOURCE_ID
        }
        viewModelScope.launch(Dispatchers.IO) {
            hostListItemDao.insert(item)
        }
        updateLogEntryType(host, type)
    }

    fun removeListItem(host: String) {
        viewModelScope.launch(Dispatchers.IO) {
            hostListItemDao.deleteUserFromHost(host)
        }
        updateLogEntryType(host, null)
    }

    private fun updateLogEntryType(host: String, type: ListType?) {
        _logs.value = _logs.value.map { entry ->
            if (entry.host == host) entry.copy(type = type) else entry
        }
    }

    private fun sortDnsRequests(sort: LogEntrySort) {
        _sort.value = sort
        _logs.value = _logs.value.sortedWith(sort.comparator())
        ExpressiveToast.makeText(
            getApplication(),
            sort.getName(),
            Toast.LENGTH_SHORT
        ).show()
    }

    private companion object {
        /**
         * How long the filtering keeps running after the screen stops listening, so a rotation
         * does not throw the filtered list away.
         */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
