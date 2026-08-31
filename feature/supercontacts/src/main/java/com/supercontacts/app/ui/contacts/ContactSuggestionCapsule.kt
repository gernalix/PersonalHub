package com.supercontacts.app.ui.contacts

import com.supercontacts.app.data.repository.AddressAutocompleteRepository
import com.supercontacts.app.data.repository.AddressSuggestion
import com.supercontacts.app.data.repository.ContactFieldSuggestion
import com.supercontacts.app.data.repository.ContactTag
import com.supercontacts.app.data.repository.ContactsRepository
import com.supercontacts.app.data.repository.ResolvedAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactSuggestionState(
    val tagSuggestions: List<ContactTag> = emptyList(),
    val addressSuggestions: List<AddressSuggestion> = emptyList(),
    val addressAutocompleteMessage: String? = null,
    val linkedPlace: ResolvedAddress? = null,
    val linkedPlaceId: String? = null,
    val fieldSuggestions: Map<String, List<ContactFieldSuggestion>> = emptyMap(),
)

class ContactSuggestionCapsule(
    private val repository: ContactsRepository,
    private val addressAutocompleteRepository: AddressAutocompleteRepository,
    private val status: ContactOperationStatusCapsule,
    private val scope: CoroutineScope,
) : ContactSuggestionOwner {
    private val mutableState = MutableStateFlow(ContactSuggestionState())
    private var tagSearchJob: Job? = null
    private var addressSearchJob: Job? = null
    private val fieldSearchJobs = mutableMapOf<String, Job>()

    override val state: StateFlow<ContactSuggestionState> = mutableState.asStateFlow()

    override fun addTagToContact(contactId: Long, tagName: String) {
        scope.launch {
            status.save {
                repository.addTagToContact(contactId, tagName)
                mutableState.value = mutableState.value.copy(tagSuggestions = emptyList())
            }
        }
    }

    override fun removeTagFromContact(contactId: Long, tagId: Long) {
        scope.launch {
            status.save {
                repository.removeTagFromContact(contactId, tagId)
            }
        }
    }

    override fun searchTagSuggestions(prefix: String) {
        tagSearchJob?.cancel()
        tagSearchJob = scope.launch {
            mutableState.value = mutableState.value.copy(tagSuggestions = repository.searchTags(prefix))
        }
    }

    override fun clearTagSuggestions() {
        tagSearchJob?.cancel()
        mutableState.value = mutableState.value.copy(tagSuggestions = emptyList())
    }

    override fun searchAddressSuggestions(query: String) {
        addressSearchJob?.cancel()
        val trimmed = query.trim()
        addressSearchJob = scope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MS)
            runCatching {
                addressAutocompleteRepository.search(trimmed)
            }.onSuccess { suggestions ->
                mutableState.value = mutableState.value.copy(
                    addressSuggestions = suggestions,
                    addressAutocompleteMessage = if (suggestions.isEmpty()) {
                        "Nessun luogo con nickname disponibile in Luoghi."
                    } else {
                        null
                    },
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    addressSuggestions = emptyList(),
                    addressAutocompleteMessage = error.message ?: "Luoghi non è disponibile.",
                )
            }
        }
    }

    override fun resolveAddressSuggestion(
        suggestion: AddressSuggestion,
        onResolved: (ResolvedAddress) -> Unit,
    ) {
        scope.launch {
            runCatching {
                addressAutocompleteRepository.resolve(suggestion)
            }.onSuccess { resolved ->
                mutableState.value = mutableState.value.copy(
                    addressSuggestions = emptyList(),
                    addressAutocompleteMessage = null,
                )
                onResolved(resolved)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    addressAutocompleteMessage =
                        error.message ?: "Could not resolve the selected address. Manual entry still works.",
                )
            }
        }
    }

    override fun resolveLinkedPlace(stableId: String) {
        scope.launch {
            runCatching { addressAutocompleteRepository.resolveStableId(stableId) }
                .onSuccess { resolved ->
                    mutableState.value = mutableState.value.copy(
                        linkedPlace = resolved,
                        linkedPlaceId = stableId,
                        addressAutocompleteMessage = if (resolved == null) {
                            "Il luogo collegato è stato eliminato. Scollegalo o scegline un altro."
                        } else {
                            null
                        },
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        linkedPlace = null,
                        linkedPlaceId = stableId,
                        addressAutocompleteMessage = error.message ?: "Luoghi non è disponibile.",
                    )
                }
        }
    }

    override fun clearAddressSuggestions() {
        addressSearchJob?.cancel()
        mutableState.value = mutableState.value.copy(
            addressSuggestions = emptyList(),
            addressAutocompleteMessage = null,
        )
    }

    override fun searchFieldSuggestions(fieldType: String, query: String) {
        fieldSearchJobs.remove(fieldType)?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            setFieldSuggestions(fieldType, emptyList())
            return
        }
        fieldSearchJobs[fieldType] = scope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MS)
            val suggestions = repository.searchFieldValueSuggestions(fieldType, trimmed)
                .filterNot { it.value.equals(trimmed, ignoreCase = true) }
            setFieldSuggestions(fieldType, suggestions)
        }
    }

    override fun clearFieldSuggestions(fieldType: String) {
        fieldSearchJobs.remove(fieldType)?.cancel()
        setFieldSuggestions(fieldType, emptyList())
    }

    private fun setFieldSuggestions(fieldType: String, suggestions: List<ContactFieldSuggestion>) {
        mutableState.value = mutableState.value.copy(
            fieldSuggestions = mutableState.value.fieldSuggestions.toMutableMap().apply {
                if (suggestions.isEmpty()) {
                    remove(fieldType)
                } else {
                    put(fieldType, suggestions)
                }
            },
        )
    }

    private companion object {
        const val AUTOCOMPLETE_DEBOUNCE_MS = 250L
    }
}
