package com.supercontacts.app.ui.contacts

import android.graphics.Bitmap
import android.net.Uri
import com.supercontacts.app.data.repository.ContactDetail
import com.supercontacts.app.data.repository.ContactInput
import com.supercontacts.app.data.repository.ContactPhotoCropSpec
import com.supercontacts.app.data.repository.ContactPhotoStore
import com.supercontacts.app.data.repository.ContactStats
import com.supercontacts.app.data.repository.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactDetailState(
    val detail: ContactDetail? = null,
    val contactStats: ContactStats? = null,
)

class ContactDetailCapsule(
    private val repository: ContactsRepository,
    private val contactPhotoStore: ContactPhotoStore,
    private val status: ContactOperationStatusCapsule,
    private val scope: CoroutineScope,
) : ContactDetailOwner {
    private val mutableState = MutableStateFlow(ContactDetailState())
    private var detailJob: Job? = null
    private var contactStatsJob: Job? = null

    override val state: StateFlow<ContactDetailState> = mutableState.asStateFlow()

    override val currentContactId: Long?
        get() = mutableState.value.detail?.id

    override fun findContactIdByPublicId(publicId: String, onResult: (Long?) -> Unit) {
        scope.launch {
            onResult(repository.findContactIdByPublicId(publicId))
        }
    }

    override fun findContactIdByPhone(number: String, onResult: (Long?) -> Unit) {
        scope.launch {
            onResult(repository.findContactIdByPhone(number))
        }
    }

    override fun observeContact(contactId: Long) {
        detailJob?.cancel()
        detailJob = scope.launch {
            repository.getContactById(contactId).collect { contact ->
                mutableState.value = mutableState.value.copy(detail = contact)
            }
        }
    }

    override fun observeContactStats(contactId: Long) {
        contactStatsJob?.cancel()
        contactStatsJob = scope.launch {
            repository.getContactStats(contactId).collect { stats ->
                mutableState.value = mutableState.value.copy(contactStats = stats)
            }
        }
    }

    override fun clearDetail() {
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(detail = null)
    }

    override fun clearContactStats() {
        contactStatsJob?.cancel()
        contactStatsJob = null
        mutableState.value = mutableState.value.copy(contactStats = null)
    }

    override fun createContact(input: ContactInput, onCreated: (Long) -> Unit) {
        scope.launch {
            status.setSaving(true)
            status.setError(null)
            runCatching {
                val contactId = repository.createContact(input)
                onCreated(contactId)
            }.onFailure { error ->
                input.photoPath.takeIf { it.isNotBlank() }?.let { contactPhotoStore.deletePhoto(it) }
                status.setError(error.message ?: "Operation failed.")
            }
            status.setSaving(false)
        }
    }

    override fun saveCroppedContactPhoto(
        sourceUri: Uri,
        cropSpec: ContactPhotoCropSpec,
        onSaved: (String) -> Unit,
    ) {
        scope.launch {
            status.setSaving(true)
            status.setError(null)
            runCatching {
                contactPhotoStore.saveCroppedPhoto(sourceUri, cropSpec)
            }.onSuccess { path ->
                onSaved(path)
            }.onFailure { error ->
                status.setError(error.message ?: "Photo could not be saved.")
            }
            status.setSaving(false)
        }
    }

    override fun saveCroppedContactPhotoForContact(
        contactId: Long,
        sourceUri: Uri,
        cropSpec: ContactPhotoCropSpec,
        onSaved: (String) -> Unit,
    ) {
        scope.launch {
            status.setSaving(true)
            status.setError(null)
            runCatching {
                contactPhotoStore.saveCroppedPhoto(
                    sourceUri = sourceUri,
                    cropSpec = cropSpec,
                    contactId = contactId,
                )
            }.onSuccess { path ->
                onSaved(path)
            }.onFailure { error ->
                status.setError(error.message ?: "Photo could not be saved.")
            }
            status.setSaving(false)
        }
    }

    override fun loadContactPhotoPreview(sourceUri: Uri, onLoaded: (Bitmap?) -> Unit) {
        scope.launch {
            runCatching {
                contactPhotoStore.loadPreviewBitmap(sourceUri)
            }.onSuccess { bitmap ->
                onLoaded(bitmap)
            }.onFailure { error ->
                status.setError(error.message ?: "Photo could not be opened.")
                onLoaded(null)
            }
        }
    }

    override fun deleteUnusedContactPhoto(path: String) {
        scope.launch {
            runCatching {
                contactPhotoStore.deletePhoto(path)
            }
        }
    }

    override fun updateContact(contactId: Long, input: ContactInput, onUpdated: () -> Unit) {
        scope.launch {
            status.setSaving(true)
            status.setError(null)
            runCatching {
                val cleanup = repository.updateContact(contactId, input)
                cleanup.oldPhotoPath?.let { contactPhotoStore.deletePhoto(it) }
                onUpdated()
            }.onFailure { error ->
                val stagedPhotoPath = input.photoPath.takeIf {
                    it.isNotBlank() && it != mutableState.value.detail?.photoPath
                }
                stagedPhotoPath?.let { contactPhotoStore.deletePhoto(it) }
                status.setError(error.message ?: "Operation failed.")
            }
            status.setSaving(false)
        }
    }

    override fun updateContactPhoto(contactId: Long, photoPath: String, onUpdated: () -> Unit) {
        scope.launch {
            status.setSaving(true)
            status.setError(null)
            runCatching {
                val cleanup = repository.updateContactPhoto(contactId, photoPath)
                cleanup.oldPhotoPath?.let { contactPhotoStore.deletePhoto(it) }
                onUpdated()
            }.onFailure { error ->
                contactPhotoStore.deletePhoto(photoPath)
                status.setError(error.message ?: "Operation failed.")
            }
            status.setSaving(false)
        }
    }

    override fun updateFieldDescription(fieldId: Long, description: String) {
        scope.launch {
            status.save {
                repository.updateFieldDescription(fieldId, description)
            }
        }
    }

    override fun ensureFieldDescriptionTargets(contactId: Long, onReady: () -> Unit) {
        scope.launch {
            runCatching {
                repository.ensureFieldDescriptionTargets(contactId)
                onReady()
            }.onFailure { error ->
                status.setError(error.message ?: "Operation failed.")
            }
        }
    }

    override fun deleteContact(contactId: Long, onDeleted: () -> Unit) {
        scope.launch {
            status.save {
                repository.deleteContact(contactId)
                onDeleted()
            }
        }
    }

    override fun recordContactOpen(contactId: Long) {
        scope.launch {
            runCatching {
                repository.recordContactOpen(contactId)
            }
        }
    }

    override fun recordFieldOpen(contactId: Long, fieldType: String) {
        scope.launch {
            runCatching {
                repository.recordFieldOpen(contactId, fieldType)
            }
        }
    }

}
