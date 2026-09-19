package com.supercontacts.app.ui.contacts

import android.graphics.Bitmap
import android.net.Uri
import com.supercontacts.app.data.repository.AddressSuggestion
import com.supercontacts.app.data.repository.ContactDetail
import com.supercontacts.app.data.repository.ContactDuplicateCandidate
import com.supercontacts.app.data.repository.ContactEvent
import com.supercontacts.app.data.repository.ContactFieldSuggestion
import com.supercontacts.app.data.repository.ContactHomeSortState
import com.supercontacts.app.data.repository.ContactInitiative
import com.supercontacts.app.data.repository.ContactPhotoCropSpec
import com.supercontacts.app.data.repository.ContactStats
import com.supercontacts.app.data.repository.ContactSummary
import com.supercontacts.app.data.repository.ContactTag
import com.supercontacts.app.data.repository.GlobalContactEvent
import com.supercontacts.app.data.repository.GlobalContactInitiative
import com.supercontacts.app.data.repository.HistoryCalendarState
import com.supercontacts.app.data.repository.HistoryDateRange
import com.supercontacts.app.data.repository.HistoryRangeDetails
import com.supercontacts.app.data.repository.InitiativeCalendarState
import com.supercontacts.app.data.repository.InitiativeDayDetails
import com.supercontacts.app.data.repository.InitiativeType
import com.supercontacts.app.data.repository.InitiativeUndoRequest
import com.supercontacts.app.data.repository.ResolvedAddress
import com.supercontacts.app.data.repository.SavedSearch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class ContactsUiState(
    val searchQuery: String = "",
    val activeTagFilters: List<ContactTag> = emptyList(),
    val availableHomeTags: List<ContactTag> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val homeSort: ContactHomeSortState = ContactHomeSortState(),
    val contacts: List<ContactSummary> = emptyList(),
    val detail: ContactDetail? = null,
    val contactStats: ContactStats? = null,
    val historyEvents: List<ContactEvent> = emptyList(),
    val globalHistoryEvents: List<GlobalContactEvent> = emptyList(),
    val globalHistoryAscending: Boolean = false,
    val historyIncludeContact: Boolean = true,
    val historyIncludeField: Boolean = true,
    val historyIncludeInitiative: Boolean = true,
    val historyCalendar: HistoryCalendarState =
        HistoryCalendarState(month = YearMonth.now(ZoneId.systemDefault())),
    val selectedHistoryRange: HistoryDateRange =
        HistoryDateRange(
            startDate = LocalDate.now(ZoneId.systemDefault()),
            endDate = LocalDate.now(ZoneId.systemDefault()),
        ),
    val historyRangeDetails: HistoryRangeDetails? = null,
    val homeInitiatives: List<GlobalContactInitiative> = emptyList(),
    val contactInitiatives: List<ContactInitiative> = emptyList(),
    val contactInitiativeAscending: Boolean = false,
    val globalInitiatives: List<GlobalContactInitiative> = emptyList(),
    val globalInitiativeAscending: Boolean = false,
    val initiativeCalendar: InitiativeCalendarState =
        InitiativeCalendarState(month = YearMonth.now(ZoneId.systemDefault())),
    val selectedInitiativeDay: LocalDate? = null,
    val initiativeDayDetails: InitiativeDayDetails? = null,
    val tagSuggestions: List<ContactTag> = emptyList(),
    val addressSuggestions: List<AddressSuggestion> = emptyList(),
    val addressAutocompleteMessage: String? = null,
    val linkedPlace: ResolvedAddress? = null,
    val linkedPlaceId: String? = null,
    val fieldSuggestions: Map<String, List<ContactFieldSuggestion>> = emptyMap(),
    val showAddedEdited: Boolean = true,
    val duplicateCandidates: List<ContactDuplicateCandidate> = emptyList(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

data class OperationStatusState(
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

interface OperationStatusOwner {
    val state: StateFlow<OperationStatusState>
    fun clearError()
}

interface ContactHomeOwner {
    val state: StateFlow<ContactHomeState>
    fun setSearchQuery(query: String)
    fun setActiveTagFilter(tag: ContactTag)
    fun setActiveTagFilters(tags: List<ContactTag>)
    fun clearTagFilter()
    fun clearSearchAndTags()
    fun saveCurrentSearch(title: String)
    fun applySavedSearch(search: SavedSearch)
    fun deleteSavedSearch(search: SavedSearch)
    fun findSavedSearchByPublicId(publicId: String, onResult: (SavedSearch?) -> Unit)
    fun addTagToContacts(contactIds: List<Long>, tagName: String)
    fun archiveContacts(contactIds: List<Long>)
    fun setHomeSort(sort: ContactHomeSortState)
    fun setHomeSortCriterion(criterion: com.supercontacts.app.data.repository.ContactHomeSort)
    fun toggleHomeSortDirection()
    fun setShowAddedEdited(show: Boolean)
}

interface ContactDetailOwner {
    val state: StateFlow<ContactDetailState>
    val currentContactId: Long?
    fun findContactIdByPublicId(publicId: String, onResult: (Long?) -> Unit)
    fun findContactIdByPhone(number: String, onResult: (Long?) -> Unit)
    fun observeContact(contactId: Long)
    fun observeContactStats(contactId: Long)
    fun clearDetail()
    fun clearContactStats()
    fun createContact(input: com.supercontacts.app.data.repository.ContactInput, onCreated: (Long) -> Unit)
    fun updateContact(contactId: Long, input: com.supercontacts.app.data.repository.ContactInput, onUpdated: () -> Unit)
    fun deleteContact(contactId: Long, onDeleted: () -> Unit)
    fun recordContactOpen(contactId: Long)
    fun recordFieldOpen(contactId: Long, fieldType: String)
    fun ensureFieldDescriptionTargets(contactId: Long, onReady: () -> Unit)
    fun updateFieldDescription(fieldId: Long, description: String)
    fun saveCroppedContactPhoto(sourceUri: Uri, cropSpec: ContactPhotoCropSpec, onSaved: (String) -> Unit)
    fun saveCroppedContactPhotoForContact(
        contactId: Long,
        sourceUri: Uri,
        cropSpec: ContactPhotoCropSpec,
        onSaved: (String) -> Unit,
    )
    fun loadContactPhotoPreview(sourceUri: Uri, onLoaded: (Bitmap?) -> Unit)
    fun deleteUnusedContactPhoto(path: String)
    fun updateContactPhoto(contactId: Long, photoPath: String, onUpdated: () -> Unit = {})
}

interface ContactMessagingOwner {
    fun scanMessagingLinks(contactId: Long)
    fun scanAllMessagingLinks()
    fun updateMessagingLinkVerificationStatus(linkId: Long, verificationStatus: String)
}

interface ContactHistoryOwner {
    val state: StateFlow<ContactHistoryState>
    fun observeContactHistory(contactId: Long)
    fun clearHistory()
    fun observeGlobalHistory()
    fun setHistoryIncludeContact(include: Boolean)
    fun setHistoryIncludeField(include: Boolean)
    fun setHistoryIncludeInitiative(include: Boolean)
    fun toggleGlobalHistorySort()
    fun clearGlobalHistory()
    fun observeHistoryCalendar()
    fun observeHistoryRange()
    fun previousHistoryMonth()
    fun nextHistoryMonth()
    fun selectHistoryDate(date: LocalDate)
    fun clearHistoryCalendar()
    fun updateHistoryTimestamp(event: ContactEvent, timestampUtc: Long)
}

interface ContactInitiativeOwner {
    val state: StateFlow<ContactInitiativeState>
    val initiativeUndoRequests: SharedFlow<InitiativeUndoRequest>
    fun observeContactInitiatives(contactId: Long)
    fun toggleContactInitiativeSort()
    fun clearContactInitiatives()
    fun observeGlobalInitiatives()
    fun toggleGlobalInitiativeSort()
    fun clearGlobalInitiatives()
    fun observeInitiativeCalendar()
    fun previousInitiativeMonth()
    fun nextInitiativeMonth()
    fun clearInitiativeCalendar()
    fun selectInitiativeDay(date: LocalDate)
    fun clearSelectedInitiativeDay()
    fun recordInitiative(contactId: Long, initiativeType: InitiativeType, contactName: String)
    fun undoInitiative(initiativeId: Long)
    fun deleteInitiative(initiativeId: Long)
}

interface ContactSuggestionOwner {
    val state: StateFlow<ContactSuggestionState>
    fun addTagToContact(contactId: Long, tagName: String)
    fun removeTagFromContact(contactId: Long, tagId: Long)
    fun searchTagSuggestions(prefix: String)
    fun clearTagSuggestions()
    fun searchAddressSuggestions(query: String)
    fun resolveAddressSuggestion(suggestion: AddressSuggestion, onResolved: (ResolvedAddress) -> Unit)
    fun resolveLinkedPlace(stableId: String)
    fun clearAddressSuggestions()
    fun searchFieldSuggestions(fieldType: String, query: String)
    fun clearFieldSuggestions(fieldType: String)
}

interface ContactDuplicateOwner {
    val state: StateFlow<ContactDuplicateState>
    fun searchDuplicateCandidates(
        input: com.supercontacts.app.data.repository.ContactInput,
        excludedContactId: Long?,
    )
    fun findStrongDuplicateBeforeSave(
        input: com.supercontacts.app.data.repository.ContactInput,
        excludedContactId: Long?,
        onResult: (ContactDuplicateCandidate?) -> Unit,
    )
    fun clearDuplicateCandidates()
}

