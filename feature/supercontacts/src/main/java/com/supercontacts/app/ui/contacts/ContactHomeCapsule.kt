package com.supercontacts.app.ui.contacts

import com.supercontacts.app.data.repository.ContactHomeSort
import com.supercontacts.app.data.repository.ContactHomeSortDirection
import com.supercontacts.app.data.repository.ContactHomeSortState
import com.supercontacts.app.data.repository.ContactSummary
import com.supercontacts.app.data.repository.ContactTag
import com.supercontacts.app.data.repository.ContactsRepository
import com.supercontacts.app.data.repository.HomePreferencesStore
import com.supercontacts.app.data.repository.SavedSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class ContactHomeState(
    val searchQuery: String = "",
    val activeTagFilters: List<ContactTag> = emptyList(),
    val availableHomeTags: List<ContactTag> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val homeSort: ContactHomeSortState = ContactHomeSortState(),
    val contacts: List<ContactSummary> = emptyList(),
    val showAddedEdited: Boolean = true,
)

class ContactHomeCapsule(
    private val repository: ContactsRepository,
    private val homePreferencesStore: HomePreferencesStore,
    scope: CoroutineScope,
) : ContactHomeOwner {
    private val searchQuery = MutableStateFlow("")
    private val activeTagFilters = MutableStateFlow<List<ContactTag>>(emptyList())
    private val homeSort = MutableStateFlow(homePreferencesStore.readSort())
    private val showAddedEdited = MutableStateFlow(homePreferencesStore.readShowAddedEdited())
    private val scope = scope

    @OptIn(ExperimentalCoroutinesApi::class)
    private val contacts: StateFlow<List<ContactSummary>> =
        combine(searchQuery, activeTagFilters, homeSort) { query, tagFilters, sort ->
            Triple(query, tagFilters, sort)
        }.flatMapLatest { (query, tagFilters, sort) ->
            repository.filterContacts(
                query = query,
                tagIds = tagFilters.map { it.id },
                sort = sort,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val availableHomeTags: StateFlow<List<ContactTag>> =
        repository.observeHomeTags().stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val savedSearches: StateFlow<List<SavedSearch>> =
        repository.observeSavedSearches().stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val baseState =
        combine(
            combine(searchQuery, activeTagFilters, availableHomeTags) { query, tagFilters, tags ->
                Triple(query, tagFilters, tags)
            },
            combine(savedSearches, homeSort, contacts) { searches, sort, contactList ->
                Triple(searches, sort, contactList)
            },
        ) { filters, homeData ->
            val (query, tagFilters, tags) = filters
            val (searches, sort, contactList) = homeData
            ContactHomeState(
                searchQuery = query,
                activeTagFilters = tagFilters,
                availableHomeTags = tags,
                savedSearches = searches,
                homeSort = sort,
                contacts = contactList,
            )
        }

    override val state: StateFlow<ContactHomeState> =
        combine(baseState, showAddedEdited) { homeState, showMetadata ->
            homeState.copy(showAddedEdited = showMetadata)
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContactHomeState(
                homeSort = homeSort.value,
                showAddedEdited = showAddedEdited.value,
            ),
        )

    override fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    override fun setActiveTagFilter(tag: ContactTag) {
        activeTagFilters.value = listOf(tag)
    }

    override fun setActiveTagFilters(tags: List<ContactTag>) {
        activeTagFilters.value = tags.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    override fun clearTagFilter() {
        activeTagFilters.value = emptyList()
    }

    override fun clearSearchAndTags() {
        searchQuery.value = ""
        activeTagFilters.value = emptyList()
    }

    override fun saveCurrentSearch(title: String) {
        val query = searchQuery.value
        val tagIds = activeTagFilters.value.map { it.id }
        scope.launch {
            repository.saveSearch(title = title, query = query, tagIds = tagIds)
        }
    }

    override fun applySavedSearch(search: SavedSearch) {
        searchQuery.value = search.query
        activeTagFilters.value = search.tags.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    override fun deleteSavedSearch(search: SavedSearch) {
        scope.launch {
            repository.deleteSavedSearch(search.id)
        }
    }

    override fun findSavedSearchByPublicId(publicId: String, onResult: (SavedSearch?) -> Unit) {
        scope.launch {
            onResult(repository.findSavedSearchByPublicId(publicId))
        }
    }

    override fun addTagToContacts(contactIds: List<Long>, tagName: String) {
        scope.launch {
            repository.addTagToContacts(contactIds, tagName)
        }
    }

    override fun archiveContacts(contactIds: List<Long>) {
        scope.launch {
            repository.archiveContacts(contactIds)
        }
    }

    override fun setHomeSort(sort: ContactHomeSortState) {
        homeSort.value = sort
        homePreferencesStore.writeSort(sort)
    }

    override fun setHomeSortCriterion(criterion: ContactHomeSort) {
        setHomeSort(homeSort.value.copy(criterion = criterion))
    }

    override fun toggleHomeSortDirection() {
        val nextDirection = if (homeSort.value.direction == ContactHomeSortDirection.ASC) {
            ContactHomeSortDirection.DESC
        } else {
            ContactHomeSortDirection.ASC
        }
        setHomeSort(homeSort.value.copy(direction = nextDirection))
    }

    override fun setShowAddedEdited(show: Boolean) {
        homePreferencesStore.writeShowAddedEdited(show)
        showAddedEdited.value = show
    }
}
