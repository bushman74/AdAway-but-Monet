package org.adaway.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.AdAwayApplication
import org.adaway.db.AppDatabase
import org.adaway.db.HostCounts
import org.adaway.db.dao.MetadataDao
import org.adaway.db.entity.ListType
import org.adaway.db.dao.HostsSourceDao
import org.adaway.helper.PreferenceHelper
import org.adaway.model.adblocking.AdBlockMethod
import org.adaway.model.adblocking.AdBlockModel
import org.adaway.model.error.HostError
import org.adaway.model.error.HostErrorException
import org.adaway.db.entity.HostsSource
import org.adaway.model.source.SourceModel
import androidx.work.WorkInfo
import org.adaway.model.source.SourceUpdateService
import org.adaway.model.source.SourceUpdateStatus
import org.adaway.model.update.Manifest
import org.adaway.model.update.UpdateModel
import org.adaway.vpn.VpnStatusRepository
import timber.log.Timber

/**
 * How far the running update has got.
 *
 * @property completed The source being dealt with, counting from one.
 * @property total How many sources the run covers.
 * @property retrieving Whether the sources are being retrieved rather than checked.
 */
data class UpdateProgress(
    val completed: Int,
    val total: Int,
    val retrieving: Boolean
)

/**
 * This class is an [AndroidViewModel] for the [HomeActivity] cards.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val sourceModel: SourceModel
    private val adBlockModel: AdBlockModel
    private val updateModel: UpdateModel

    private val database: AppDatabase
    private val hostsSourceDao: HostsSourceDao
    private val metadataDao: MetadataDao

    private val _pending = MutableStateFlow(false)

    /**
     * The state of the update the user asked for, which now runs outside this view model.
     */
    private val manualUpdate = SourceUpdateService.observeManualWork(application)
        .asFlow()
        .map { infos -> infos.firstOrNull() }

    /**
     * Whether something is running right now.
     *
     * Only work that is actually running counts. Work that is merely waiting, for its turn or for
     * a retry, used to count too, and since stopping the application leaves its work enqueued, the
     * screen came back showing a progress bar for an update that was not running at all.
     */
    val pending: StateFlow<Boolean> = combine(
        _pending,
        manualUpdate.map { it?.state == WorkInfo.State.RUNNING }
    ) { local, updating -> local || updating }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), false)

    /**
     * What the running update is doing, or `null` when nothing is running.
     */
    val updateProgress: StateFlow<UpdateProgress?> = manualUpdate
        .map { info ->
            if (info?.state != WorkInfo.State.RUNNING) {
                return@map null
            }
            val total = info.progress.getInt(SourceUpdateService.KEY_PROGRESS_TOTAL, 0)
            if (total <= 0) {
                return@map null
            }
            UpdateProgress(
                // The listener reports how many are done, the screen counts the one in hand.
                completed = (info.progress.getInt(SourceUpdateService.KEY_PROGRESS_COMPLETED, 0) + 1)
                    .coerceAtMost(total),
                total = total,
                retrieving = info.progress.getBoolean(
                    SourceUpdateService.KEY_PROGRESS_RETRIEVING, false
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), null)

    private val _error = MutableSharedFlow<HostError>()
    val error: SharedFlow<HostError> = _error

    init {
        val awayApplication = application as AdAwayApplication
        sourceModel = awayApplication.sourceModel
        adBlockModel = awayApplication.adBlockModel
        updateModel = awayApplication.updateModel

        database = AppDatabase.getInstance(application)
        hostsSourceDao = database.hostsSourceDao()
        metadataDao = database.metadataDao()

        refreshHostCounts()
        observeManualUpdate()

        VpnStatusRepository.update(PreferenceHelper.getVpnServiceStatus(application))
    }

    val state: StateFlow<String> = merge(
        sourceModel.state.asFlow(),
        adBlockModel.state.asFlow()
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), "")

    val adBlocked: StateFlow<Boolean> = combine(
        adBlockModel.isApplied.asFlow().map { it == true },
        VpnStatusRepository.status
    ) { applied, vpnStatus ->
        if (adBlockModel.method == AdBlockMethod.VPN) {
            vpnStatus.isStarted
        } else {
            applied
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS),
        adBlockModel.isApplied.value == true
    )

    val updateAvailable: StateFlow<Boolean> = sourceModel.isUpdateAvailable
        .asFlow()
        .map { it == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), false)

    val versionName: String get() = updateModel.versionName

    val appManifest: StateFlow<Manifest?> = updateModel.manifest
        .asFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), updateModel.manifest.value)

    /**
     * The host counters, read from their cached values so the screen shows them at once rather
     * than counting distinct hosts across millions of rows on every display.
     */
    private fun cachedHostCount(type: ListType): StateFlow<Int?> = metadataDao.observeHostCount(type)
        .asFlow()
        .map { it?.toIntOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), null)

    val blockedHostCount: StateFlow<Int?> = cachedHostCount(ListType.BLOCKED)

    val allowedHostCount: StateFlow<Int?> = cachedHostCount(ListType.ALLOWED)

    val redirectHostCount: StateFlow<Int?> = cachedHostCount(ListType.REDIRECTED)

    /**
     * Set while the confirmation that every source is up to date is being shown.
     * Cleared on its own so the summary returns to its usual content.
     */
    private val _allSourcesUpToDate = MutableStateFlow(false)
    val allSourcesUpToDate: StateFlow<Boolean> = _allSourcesUpToDate

    /**
     * Whether an update was asked for here and its outcome is still to be reported.
     */
    private var awaitingUpdateOutcome = false

    /**
     * The enabled sources, classified into up to date and outdated.
     * Both counts come from the same list so they always add up to the number of enabled sources.
     */
    private val enabledSources = hostsSourceDao.loadAll()
        .asFlow()
        .map { sources -> sources.filter { it.isEnabled } }

    val upToDateSourceCount: StateFlow<Int> = enabledSources
        .map { sources -> sources.count { it.isUpToDate() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), 0)

    val outdatedSourceCount: StateFlow<Int> = enabledSources
        .map { sources -> sources.count { !it.isUpToDate() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), 0)

    private fun HostsSource.isUpToDate(): Boolean =
        SourceUpdateStatus.isUpToDate(localModificationDate, onlineModificationDate)

    /**
     * Report the outcome of the update the user asked for.
     *
     * Only an update asked for while this screen is alive is reported. The work keeps its last
     * result and hands it to every new observer, so without this the outcome of an update from a
     * previous run would be announced again every time the application is opened.
     */
    private fun observeManualUpdate() {
        viewModelScope.launch {
            manualUpdate.collect { info ->
                if (info == null || !info.state.isFinished || !awaitingUpdateOutcome) {
                    return@collect
                }
                awaitingUpdateOutcome = false
                when {
                    info.state == WorkInfo.State.SUCCEEDED -> {
                        if (info.outputData.getBoolean(SourceUpdateService.KEY_UP_TO_DATE, false)) {
                            confirmAllSourcesUpToDate()
                        }
                        refreshHostCounts()
                    }

                    info.state == WorkInfo.State.FAILED -> {
                        info.outputData.getString(SourceUpdateService.KEY_ERROR)
                            ?.let { name -> runCatching { HostError.valueOf(name) }.getOrNull() }
                            ?.let { _error.emit(it) }
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Show that every source is up to date, then return the summary to its usual content.
     */
    private fun confirmAllSourcesUpToDate() {
        viewModelScope.launch {
            _allSourcesUpToDate.value = true
            delay(UP_TO_DATE_CONFIRMATION_MILLIS)
            _allSourcesUpToDate.value = false
        }
    }

    /**
     * Recompute the cached host counters off the main thread.
     */
    private fun refreshHostCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            HostCounts.refresh(database)
        }
    }

    fun checkForAppUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            updateModel.checkForUpdate()
        }
    }

    fun toggleAdBlocking() {
        if (_pending.value) {
            return
        }
        viewModelScope.launch {
            try {
                _pending.value = true
                withContext(Dispatchers.IO) {
                    if (adBlocked.value) {
                        adBlockModel.revert()
                    } else {
                        adBlockModel.apply()
                    }
                }
            } catch (exception: HostErrorException) {
                Timber.w(exception, "Failed to toggle ad blocking.")
                _error.emit(exception.error)
            } finally {
                _pending.value = false
            }
        }
    }

    /**
     * Check the sources for update and retrieve them when at least one is outdated.
     *
     * Enqueued rather than run here: it used to run in this view model's scope, so leaving the
     * screen cancelled the update part way through.
     */
    fun update() {
        awaitingUpdateOutcome = true
        SourceUpdateService.runNow(getApplication(), false)
    }

    /**
     * Retrieve the sources unconditionally, without checking them for update first.
     */
    fun sync() {
        awaitingUpdateOutcome = true
        SourceUpdateService.runNow(getApplication(), true)
    }

    fun enableAllSources() {
        viewModelScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                sourceModel.enableAllSources()
            }
            if (enabled) {
                sync()
            }
        }
    }

    companion object {
        private const val FLOW_STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * How long the up to date confirmation stays on screen.
         */
        private const val UP_TO_DATE_CONFIRMATION_MILLIS = 2_500L
    }
}
