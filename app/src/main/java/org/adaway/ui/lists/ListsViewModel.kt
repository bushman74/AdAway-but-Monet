package org.adaway.ui.lists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import org.adaway.db.AppDatabase
import org.adaway.db.dao.HostListItemDao
import org.adaway.db.dao.HostsSourceDao
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.HostsSource
import org.adaway.db.entity.HostsSource.USER_SOURCE_ID
import org.adaway.db.entity.ListType
import org.adaway.db.entity.ListType.ALLOWED
import org.adaway.db.entity.ListType.BLOCKED
import org.adaway.db.entity.ListType.REDIRECTED
import org.adaway.db.entity.ListedHost
import org.adaway.db.dao.HostListQueries
import org.adaway.helper.PreferenceHelper
import org.adaway.ui.search.SearchOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ListsViewModel(application: Application) : AndroidViewModel(application) {
    private val hostListItemDao: HostListItemDao = AppDatabase.getInstance(application).hostsListItemDao()
    private val hostsSourceDao: HostsSourceDao = AppDatabase.getInstance(application).hostsSourceDao()
    private val query = MutableStateFlow("")
    private val _searchOptions = MutableStateFlow(PreferenceHelper.getSearchOptions(application))

    /**
     * How the search matches, kept across searches and screens.
     */
    val searchOptions: StateFlow<SearchOptions> = _searchOptions.asStateFlow()

    /**
     * The condition the search puts on the hosts, or `null` for none. While the user types, it
     * waits for a pause, so the lists are not searched again for every letter: each search of a
     * large list reads all its hosts. Clearing the search applies at once.
     */
    private val search: Flow<HostSearch?> =
        combine(query, _searchOptions) { text, options -> HostSearch.of(text, options) }
            .distinctUntilChanged()
            .debounce { condition -> if (condition == null) 0L else SEARCH_DELAY_MILLIS }

    /**
     * The name of each source by its id, to label every host with the sources listing it.
     * The user's source is not included, so the hosts the user added keep their usual look.
     */
    val sourceLabels: StateFlow<Map<Int, String>> = hostsSourceDao.loadAll().asFlow()
        .map { sources -> sources.associate { it.id to it.label } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Placeholders are disabled deliberately. Room counts the whole result set on every load to
     * size them, and counting a grouped query over millions of rows had to finish before the first
     * page could be shown. Without them the first page is read directly.
     */
    private val pagingConfig = PagingConfig(
        pageSize = 50,
        initialLoadSize = 150,
        enablePlaceholders = false
    )

    val blocked = ListPage(BLOCKED)
    val allowed = ListPage(ALLOWED)
    val redirected = ListPage(REDIRECTED)

    private val _modelChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val modelChanged: SharedFlow<Unit> = _modelChanged.asSharedFlow()

    fun toggleItemEnabled(item: HostListItem) {
        item.isEnabled = !item.isEnabled
        viewModelScope.launch(Dispatchers.IO) {
            hostListItemDao.update(item)
            _modelChanged.emit(Unit)
        }
    }

    fun addListItem(type: ListType, host: String, redirection: String?) {
        val item = HostListItem().apply {
            this.type = type
            this.host = host
            this.redirection = redirection
            isEnabled = true
            sourceId = USER_SOURCE_ID
        }
        viewModelScope.launch(Dispatchers.IO) {
            val id = hostListItemDao.getHostId(host)
            if (id.isPresent) {
                item.id = id.get()
                hostListItemDao.update(item)
            } else {
                hostListItemDao.insert(item)
            }
            _modelChanged.emit(Unit)
        }
    }

    fun updateListItem(item: HostListItem, host: String, redirection: String?) {
        item.host = host
        item.redirection = redirection
        viewModelScope.launch(Dispatchers.IO) {
            hostListItemDao.update(item)
            _modelChanged.emit(Unit)
        }
    }

    fun removeListItem(list: HostListItem) {
        viewModelScope.launch(Dispatchers.IO) {
            hostListItemDao.delete(list)
            _modelChanged.emit(Unit)
        }
    }

    fun search(query: String) {
        this.query.value = query
    }

    fun clearSearch() {
        query.value = ""
    }

    fun setSearchOptions(options: SearchOptions) {
        _searchOptions.value = options
        PreferenceHelper.setSearchOptions(getApplication(), options)
    }

    /**
     * One tab of the screen: its hosts, the sources they can be narrowed to, and the one chosen.
     * The search applies to every tab, while each tab keeps its own source.
     */
    inner class ListPage(private val type: ListType) {
        private val _selectedSource = MutableStateFlow<Int?>(null)

        /**
         * The id of the source whose hosts alone are shown, [USER_SOURCE_ID] for the hosts the user
         * added, or `null` for the hosts of every source.
         */
        val selectedSource: StateFlow<Int?> = _selectedSource.asStateFlow()

        /**
         * The sources listing at least one host of this tab, offered as filters. When the chosen
         * one stops listing any, for instance once it is disabled and cleared, the tab goes back
         * to every source rather than staying empty behind a filter no longer shown.
         */
        val sources: StateFlow<List<HostsSource>> =
            hostsSourceDao.loadListingSources(type.value).asFlow()
                .onEach { listing ->
                    val selected = _selectedSource.value
                    if (selected != null && selected != USER_SOURCE_ID &&
                        listing.none { it.id == selected }
                    ) {
                        _selectedSource.value = null
                    }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /**
         * Whether the user added hosts to this list, which is when the Personal filter is offered.
         * When the last one is removed while that filter is chosen, the tab goes back to every
         * source, as for a source that stops listing hosts.
         */
        val hasPersonalHosts: StateFlow<Boolean> =
            hostListItemDao.hasUserHosts(type.value).asFlow()
                .onEach { has ->
                    if (!has && _selectedSource.value == USER_SOURCE_ID) {
                        _selectedSource.value = null
                    }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        val items: Flow<PagingData<ListedHost>> =
            combine(search, _selectedSource) { condition, sourceId -> condition to sourceId }
                .flatMapLatest { (condition, sourceId) ->
                    Pager(pagingConfig) {
                        hostListItemDao.loadList(
                            if (sourceId == null) {
                                HostListQueries.list(type.value, condition?.sql, condition?.argument)
                            } else {
                                HostListQueries.sourceList(
                                    type.value, sourceId, condition?.sql, condition?.argument
                                )
                            }
                        )
                    }.flow
                        // A new search or source starts from an empty list that is loading, so the
                        // screen shows the spinner until its results arrive, as when it opens,
                        // instead of leaving the previous results in place meanwhile.
                        .onStart { emit(PagingData.empty(LOADING)) }
                }
                .cachedIn(viewModelScope)

        fun selectSource(sourceId: Int?) {
            _selectedSource.value = sourceId
        }
    }
}

/**
 * The state of a list whose first page is being read.
 */
private val LOADING = LoadStates(
    refresh = LoadState.Loading,
    prepend = LoadState.NotLoading(endOfPaginationReached = false),
    append = LoadState.NotLoading(endOfPaginationReached = false)
)

/**
 * How long the search waits for the user to stop typing before reading the lists.
 */
private const val SEARCH_DELAY_MILLIS = 300L
