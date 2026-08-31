package com.supercontacts.app.ui.contacts

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.supercontacts.app.data.backup.SuperContactsBackupManager
import com.supercontacts.app.data.repository.AddressAutocompleteRepository
import com.supercontacts.app.data.repository.AddressSuggestion
import com.supercontacts.app.data.repository.ContactDuplicateCandidate
import com.supercontacts.app.data.repository.ContactEvent
import com.supercontacts.app.data.repository.ContactHomeSort
import com.supercontacts.app.data.repository.ContactHomeSortState
import com.supercontacts.app.data.repository.ContactInput
import com.supercontacts.app.data.repository.ContactPhotoCropSpec
import com.supercontacts.app.data.repository.ContactPhotoStore
import com.supercontacts.app.data.repository.ContactTag
import com.supercontacts.app.data.repository.ContactsRepository
import com.supercontacts.app.data.repository.HomePreferencesStore
import com.supercontacts.app.data.repository.InitiativeType
import com.supercontacts.app.data.repository.InitiativeUndoRequest
import com.supercontacts.app.data.repository.MessagingLinkVerificationStatus
import com.supercontacts.app.data.repository.ResolvedAddress
import com.supercontacts.app.data.repository.SavedSearch
import java.time.LocalDate
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ContactsViewModel(
    repository: ContactsRepository,
    homePreferencesStore: HomePreferencesStore,
    addressAutocompleteRepository: AddressAutocompleteRepository,
    contactPhotoStore: ContactPhotoStore,
    backupManager: SuperContactsBackupManager,
) : ViewModel() {
    private val statusOwner = ContactOperationStatusCapsule()
    private val detailOwner = ContactDetailCapsule(
        repository = repository,
        contactPhotoStore = contactPhotoStore,
        status = statusOwner,
        scope = viewModelScope,
    )
    private val homeOwner = ContactHomeCapsule(
        repository = repository,
        homePreferencesStore = homePreferencesStore,
        scope = viewModelScope,
    )
    private val messagingOwner = ContactMessagingCapsule(
        repository = repository,
        status = statusOwner,
        scope = viewModelScope,
    )
    private val historyOwner = ContactHistoryCapsule(
        repository = repository,
        status = statusOwner,
        currentContactId = { detailOwner.currentContactId },
        scope = viewModelScope,
    )
    private val initiativeOwner = ContactInitiativeCapsule(
        repository = repository,
        status = statusOwner,
        currentContactId = { detailOwner.currentContactId },
        scope = viewModelScope,
    )
    private val suggestionOwner = ContactSuggestionCapsule(
        repository = repository,
        addressAutocompleteRepository = addressAutocompleteRepository,
        status = statusOwner,
        scope = viewModelScope,
    )
    private val duplicateOwner = ContactDuplicateCapsule(
        repository = repository,
        status = statusOwner,
        scope = viewModelScope,
    )
    private val backupOwner = ContactBackupCapsule(
        backupManager = backupManager,
        status = statusOwner,
        migrateLegacyContactPhotos = detailOwner::migrateLegacyContactPhotosIfPossible,
        scope = viewModelScope,
    )

    val initiativeUndoRequests: SharedFlow<InitiativeUndoRequest> =
        initiativeOwner.initiativeUndoRequests

    val uiState: StateFlow<ContactsUiState> =
        combine(
            homeOwner.state,
            detailOwner.state,
            historyOwner.state,
            initiativeOwner.state,
            suggestionOwner.state,
            duplicateOwner.state,
            backupOwner.state,
            statusOwner.state,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val home = values[0] as ContactHomeState
            val detail = values[1] as ContactDetailState
            val history = values[2] as ContactHistoryState
            val initiative = values[3] as ContactInitiativeState
            val suggestion = values[4] as ContactSuggestionState
            val duplicate = values[5] as ContactDuplicateState
            val backup = values[6] as ContactBackupState
            val status = values[7] as OperationStatusState
            ContactsUiState(
                searchQuery = home.searchQuery,
                activeTagFilters = home.activeTagFilters,
                availableHomeTags = home.availableHomeTags,
                savedSearches = home.savedSearches,
                homeSort = home.homeSort,
                contacts = home.contacts,
                detail = detail.detail,
                contactStats = detail.contactStats,
                historyEvents = history.historyEvents,
                globalHistoryEvents = history.globalHistoryEvents,
                globalHistoryAscending = history.globalHistoryAscending,
                historyIncludeContact = history.historyIncludeContact,
                historyIncludeField = history.historyIncludeField,
                historyIncludeInitiative = history.historyIncludeInitiative,
                historyCalendar = history.historyCalendar,
                selectedHistoryRange = history.selectedHistoryRange,
                historyRangeDetails = history.historyRangeDetails,
                homeInitiatives = initiative.homeInitiatives,
                contactInitiatives = initiative.contactInitiatives,
                contactInitiativeAscending = initiative.contactInitiativeAscending,
                globalInitiatives = initiative.globalInitiatives,
                globalInitiativeAscending = initiative.globalInitiativeAscending,
                initiativeCalendar = initiative.initiativeCalendar,
                selectedInitiativeDay = initiative.selectedInitiativeDay,
                initiativeDayDetails = initiative.initiativeDayDetails,
                tagSuggestions = suggestion.tagSuggestions,
                addressSuggestions = suggestion.addressSuggestions,
                addressAutocompleteMessage = suggestion.addressAutocompleteMessage,
                linkedPlace = suggestion.linkedPlace,
                linkedPlaceId = suggestion.linkedPlaceId,
                fieldSuggestions = suggestion.fieldSuggestions,
                showAddedEdited = home.showAddedEdited,
                duplicateCandidates = duplicate.duplicateCandidates,
                backupState = backup.backupState,
                isSaving = status.isSaving,
                isBackupRunning = backup.isBackupRunning,
                errorMessage = status.errorMessage,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContactsUiState(),
        )

    init {
        detailOwner.migrateLegacyContactPhotosIfPossible()
    }

    fun setSearchQuery(query: String) = homeOwner.setSearchQuery(query)
    fun setActiveTagFilter(tag: ContactTag) = homeOwner.setActiveTagFilter(tag)
    fun setActiveTagFilters(tags: List<ContactTag>) = homeOwner.setActiveTagFilters(tags)
    fun clearTagFilter() = homeOwner.clearTagFilter()
    fun clearSearchAndTags() = homeOwner.clearSearchAndTags()
    fun saveCurrentSearch(title: String) = homeOwner.saveCurrentSearch(title)
    fun applySavedSearch(search: SavedSearch) = homeOwner.applySavedSearch(search)
    fun deleteSavedSearch(search: SavedSearch) = homeOwner.deleteSavedSearch(search)
    fun findSavedSearchByPublicId(publicId: String, onResult: (SavedSearch?) -> Unit) =
        homeOwner.findSavedSearchByPublicId(publicId, onResult)
    fun addTagToContacts(contactIds: List<Long>, tagName: String) =
        homeOwner.addTagToContacts(contactIds, tagName)
    fun archiveContacts(contactIds: List<Long>) = homeOwner.archiveContacts(contactIds)
    fun setHomeSort(sort: ContactHomeSortState) = homeOwner.setHomeSort(sort)
    fun setHomeSortCriterion(criterion: ContactHomeSort) = homeOwner.setHomeSortCriterion(criterion)
    fun toggleHomeSortDirection() = homeOwner.toggleHomeSortDirection()
    fun setShowAddedEdited(show: Boolean) = homeOwner.setShowAddedEdited(show)

    fun findContactIdByPublicId(publicId: String, onResult: (Long?) -> Unit) =
        detailOwner.findContactIdByPublicId(publicId, onResult)
    fun findContactIdByPhone(number: String, onResult: (Long?) -> Unit) =
        detailOwner.findContactIdByPhone(number, onResult)
    fun observeContact(contactId: Long) = detailOwner.observeContact(contactId)
    fun observeContactStats(contactId: Long) = detailOwner.observeContactStats(contactId)
    fun clearDetail() = detailOwner.clearDetail()
    fun clearContactStats() = detailOwner.clearContactStats()
    fun createContact(input: ContactInput, onCreated: (Long) -> Unit) =
        detailOwner.createContact(input, onCreated)
    fun updateContact(contactId: Long, input: ContactInput, onUpdated: () -> Unit) =
        detailOwner.updateContact(contactId, input, onUpdated)
    fun deleteContact(contactId: Long, onDeleted: () -> Unit) =
        detailOwner.deleteContact(contactId, onDeleted)
    fun recordContactOpen(contactId: Long) = detailOwner.recordContactOpen(contactId)
    fun recordFieldOpen(contactId: Long, fieldType: String) =
        detailOwner.recordFieldOpen(contactId, fieldType)
    fun ensureFieldDescriptionTargets(contactId: Long, onReady: () -> Unit) =
        detailOwner.ensureFieldDescriptionTargets(contactId, onReady)
    fun updateFieldDescription(fieldId: Long, description: String) =
        detailOwner.updateFieldDescription(fieldId, description)
    fun saveCroppedContactPhoto(sourceUri: Uri, cropSpec: ContactPhotoCropSpec, onSaved: (String) -> Unit) =
        detailOwner.saveCroppedContactPhoto(sourceUri, cropSpec, onSaved)
    fun saveCroppedContactPhotoForContact(
        contactId: Long,
        sourceUri: Uri,
        cropSpec: ContactPhotoCropSpec,
        onSaved: (String) -> Unit,
    ) = detailOwner.saveCroppedContactPhotoForContact(contactId, sourceUri, cropSpec, onSaved)
    fun loadContactPhotoPreview(sourceUri: Uri, onLoaded: (Bitmap?) -> Unit) =
        detailOwner.loadContactPhotoPreview(sourceUri, onLoaded)
    fun deleteUnusedContactPhoto(path: String) = detailOwner.deleteUnusedContactPhoto(path)
    fun updateContactPhoto(contactId: Long, photoPath: String, onUpdated: () -> Unit = {}) =
        detailOwner.updateContactPhoto(contactId, photoPath, onUpdated)

    fun scanMessagingLinks(contactId: Long) =
        messagingOwner.scanMessagingLinks(contactId)
    fun scanAllMessagingLinks() =
        messagingOwner.scanAllMessagingLinks()
    fun confirmMessagingLink(linkId: Long) =
        messagingOwner.updateMessagingLinkVerificationStatus(
            linkId,
            MessagingLinkVerificationStatus.ManuallyConfirmed,
        )
    fun rejectMessagingLink(linkId: Long) =
        messagingOwner.updateMessagingLinkVerificationStatus(
            linkId,
            MessagingLinkVerificationStatus.ManuallyRejected,
        )
    fun resetMessagingLinkVerification(linkId: Long) =
        messagingOwner.updateMessagingLinkVerificationStatus(
            linkId,
            MessagingLinkVerificationStatus.Unverified,
        )

    fun observeContactHistory(contactId: Long) = historyOwner.observeContactHistory(contactId)
    fun clearHistory() = historyOwner.clearHistory()
    fun observeGlobalHistory() = historyOwner.observeGlobalHistory()
    fun setHistoryIncludeContact(include: Boolean) = historyOwner.setHistoryIncludeContact(include)
    fun setHistoryIncludeField(include: Boolean) = historyOwner.setHistoryIncludeField(include)
    fun setHistoryIncludeInitiative(include: Boolean) = historyOwner.setHistoryIncludeInitiative(include)
    fun toggleGlobalHistorySort() = historyOwner.toggleGlobalHistorySort()
    fun clearGlobalHistory() = historyOwner.clearGlobalHistory()
    fun observeHistoryCalendar() = historyOwner.observeHistoryCalendar()
    fun observeHistoryRange() = historyOwner.observeHistoryRange()
    fun previousHistoryMonth() = historyOwner.previousHistoryMonth()
    fun nextHistoryMonth() = historyOwner.nextHistoryMonth()
    fun selectHistoryDate(date: LocalDate) = historyOwner.selectHistoryDate(date)
    fun clearHistoryCalendar() = historyOwner.clearHistoryCalendar()
    fun updateHistoryTimestamp(event: ContactEvent, timestampUtc: Long) =
        historyOwner.updateHistoryTimestamp(event, timestampUtc)

    fun observeContactInitiatives(contactId: Long) = initiativeOwner.observeContactInitiatives(contactId)
    fun toggleContactInitiativeSort() = initiativeOwner.toggleContactInitiativeSort()
    fun clearContactInitiatives() = initiativeOwner.clearContactInitiatives()
    fun observeGlobalInitiatives() = initiativeOwner.observeGlobalInitiatives()
    fun toggleGlobalInitiativeSort() = initiativeOwner.toggleGlobalInitiativeSort()
    fun clearGlobalInitiatives() = initiativeOwner.clearGlobalInitiatives()
    fun observeInitiativeCalendar() = initiativeOwner.observeInitiativeCalendar()
    fun previousInitiativeMonth() = initiativeOwner.previousInitiativeMonth()
    fun nextInitiativeMonth() = initiativeOwner.nextInitiativeMonth()
    fun clearInitiativeCalendar() = initiativeOwner.clearInitiativeCalendar()
    fun selectInitiativeDay(date: LocalDate) = initiativeOwner.selectInitiativeDay(date)
    fun clearSelectedInitiativeDay() = initiativeOwner.clearSelectedInitiativeDay()
    fun recordInitiative(contactId: Long, initiativeType: InitiativeType, contactName: String) =
        initiativeOwner.recordInitiative(contactId, initiativeType, contactName)
    fun undoInitiative(initiativeId: Long) = initiativeOwner.undoInitiative(initiativeId)
    fun deleteInitiative(initiativeId: Long) = initiativeOwner.deleteInitiative(initiativeId)

    fun addTagToContact(contactId: Long, tagName: String) =
        suggestionOwner.addTagToContact(contactId, tagName)
    fun removeTagFromContact(contactId: Long, tagId: Long) =
        suggestionOwner.removeTagFromContact(contactId, tagId)
    fun searchTagSuggestions(prefix: String) = suggestionOwner.searchTagSuggestions(prefix)
    fun clearTagSuggestions() = suggestionOwner.clearTagSuggestions()
    fun searchAddressSuggestions(query: String) = suggestionOwner.searchAddressSuggestions(query)
    fun resolveAddressSuggestion(suggestion: AddressSuggestion, onResolved: (ResolvedAddress) -> Unit) =
        suggestionOwner.resolveAddressSuggestion(suggestion, onResolved)
    fun resolveLinkedPlace(stableId: String) = suggestionOwner.resolveLinkedPlace(stableId)
    fun clearAddressSuggestions() = suggestionOwner.clearAddressSuggestions()
    fun searchFieldSuggestions(fieldType: String, query: String) =
        suggestionOwner.searchFieldSuggestions(fieldType, query)
    fun clearFieldSuggestions(fieldType: String) = suggestionOwner.clearFieldSuggestions(fieldType)

    fun searchDuplicateCandidates(input: ContactInput, excludedContactId: Long?) =
        duplicateOwner.searchDuplicateCandidates(input, excludedContactId)
    fun findStrongDuplicateBeforeSave(
        input: ContactInput,
        excludedContactId: Long?,
        onResult: (ContactDuplicateCandidate?) -> Unit,
    ) = duplicateOwner.findStrongDuplicateBeforeSave(input, excludedContactId, onResult)
    fun clearDuplicateCandidates() = duplicateOwner.clearDuplicateCandidates()

    fun clearError() = statusOwner.clearError()
    fun setBackupFolder(uri: Uri) = backupOwner.setBackupFolder(uri)
    fun setAutoExportEnabled(enabled: Boolean) = backupOwner.setAutoExportEnabled(enabled)
    fun exportBackupNow() = backupOwner.exportBackupNow()
    fun importBackup(uri: Uri) = backupOwner.importBackup(uri)
    fun importBackupFolder(uri: Uri) = backupOwner.importBackupFolder(uri)

    class Factory(
        private val repository: ContactsRepository,
        private val homePreferencesStore: HomePreferencesStore,
        private val addressAutocompleteRepository: AddressAutocompleteRepository,
        private val contactPhotoStore: ContactPhotoStore,
        private val backupManager: SuperContactsBackupManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ContactsViewModel::class.java)) {
                return ContactsViewModel(
                    repository = repository,
                    homePreferencesStore = homePreferencesStore,
                    addressAutocompleteRepository = addressAutocompleteRepository,
                    contactPhotoStore = contactPhotoStore,
                    backupManager = backupManager,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
