package com.supercontacts.app.ui.contacts

import com.supercontacts.app.data.repository.ContactDuplicateCandidate
import com.supercontacts.app.data.repository.ContactInput
import com.supercontacts.app.data.repository.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactDuplicateState(
    val duplicateCandidates: List<ContactDuplicateCandidate> = emptyList(),
)

class ContactDuplicateCapsule(
    private val repository: ContactsRepository,
    private val status: ContactOperationStatusCapsule,
    private val scope: CoroutineScope,
) : ContactDuplicateOwner {
    private val mutableState = MutableStateFlow(ContactDuplicateState())
    private var duplicateSearchJob: Job? = null

    override val state: StateFlow<ContactDuplicateState> = mutableState.asStateFlow()

    override fun searchDuplicateCandidates(input: ContactInput, excludedContactId: Long?) {
        duplicateSearchJob?.cancel()
        duplicateSearchJob = scope.launch {
            delay(300)
            mutableState.value = ContactDuplicateState(
                duplicateCandidates = repository.findDuplicateCandidates(
                    input = input,
                    excludedContactId = excludedContactId,
                    limit = 5,
                ),
            )
        }
    }

    override fun findStrongDuplicateBeforeSave(
        input: ContactInput,
        excludedContactId: Long?,
        onResult: (ContactDuplicateCandidate?) -> Unit,
    ) {
        scope.launch {
            runCatching {
                repository.findDuplicateCandidates(
                    input = input,
                    excludedContactId = excludedContactId,
                    limit = 5,
                ).firstOrNull { it.hasStrongMatch }
            }.onSuccess(onResult)
                .onFailure { error ->
                    status.setError(error.message ?: "Operation failed.")
                    onResult(null)
                }
        }
    }

    override fun clearDuplicateCandidates() {
        duplicateSearchJob?.cancel()
        mutableState.value = ContactDuplicateState()
    }
}
