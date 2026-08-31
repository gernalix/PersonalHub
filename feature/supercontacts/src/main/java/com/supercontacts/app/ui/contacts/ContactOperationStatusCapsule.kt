package com.supercontacts.app.ui.contacts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ContactOperationStatusCapsule : OperationStatusOwner {
    private val mutableState = MutableStateFlow(OperationStatusState())

    override val state: StateFlow<OperationStatusState> = mutableState.asStateFlow()

    fun setSaving(saving: Boolean) {
        mutableState.value = mutableState.value.copy(isSaving = saving)
    }

    fun setError(message: String?) {
        mutableState.value = mutableState.value.copy(errorMessage = message)
    }

    override fun clearError() {
        setError(null)
    }

    suspend fun save(
        fallbackError: String = "Operation failed.",
        block: suspend () -> Unit,
    ) {
        setSaving(true)
        setError(null)
        runCatching { block() }
            .onFailure { error -> setError(error.message ?: fallbackError) }
        setSaving(false)
    }
}
