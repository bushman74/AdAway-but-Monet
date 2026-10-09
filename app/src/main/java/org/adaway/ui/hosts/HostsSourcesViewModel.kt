package org.adaway.ui.hosts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.adaway.db.AppDatabase
import org.adaway.db.dao.HostsSourceDao
import org.adaway.db.dao.MetadataDao
import org.adaway.db.entity.HostsSource
import org.adaway.db.entity.ListType

class HostsSourcesViewModel(application: Application) : AndroidViewModel(application) {
    private val hostsSourceDao: HostsSourceDao = AppDatabase.getInstance(application).hostsSourceDao()

    private val metadataDao: MetadataDao = AppDatabase.getInstance(application).metadataDao()

    val hostsSources: StateFlow<List<HostsSource>> = hostsSourceDao.loadAll()
        .asFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * The number of distinct hosts blocked, as the home screen shows it, or `null` until known.
     * Adding up the sizes of the sources instead counted their allowed and redirected entries and
     * every host listed by several sources more than once.
     */
    val blockedHostCount: StateFlow<Int?> = metadataDao.observeHostCount(ListType.BLOCKED)
        .asFlow()
        .map { it?.toIntOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), null)

    /**
     * Emits when the user changes the sources, so the screen can offer to apply the configuration.
     *
     * Driven by the actions that change something rather than by observing the source list: the
     * list is shared with an empty initial value, so its first real emission is the initial load
     * and looked exactly like a change.
     */
    private val _sourcesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sourcesChanged: SharedFlow<Unit> = _sourcesChanged

    fun toggleSourceEnabled(source: HostsSource) {
        viewModelScope.launch(Dispatchers.IO) {
            hostsSourceDao.toggleEnabled(source)
            _sourcesChanged.emit(Unit)
        }
    }

    companion object {
        private const val FLOW_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
