package com.supercontacts.app.ui.contacts

import android.net.Uri
import com.supercontacts.app.data.backup.BackupState
import com.supercontacts.app.data.backup.SuperContactsBackupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactBackupState(
    val backupState: BackupState = BackupState(),
    val isBackupRunning: Boolean = false,
)

class ContactBackupCapsule(
    private val backupManager: SuperContactsBackupManager,
    private val status: ContactOperationStatusCapsule,
    private val migrateLegacyContactPhotos: () -> Unit,
    private val scope: CoroutineScope,
) : ContactBackupOwner {
    private val mutableState = MutableStateFlow(ContactBackupState(backupState = backupManager.state.value))

    override val state: StateFlow<ContactBackupState> = mutableState.asStateFlow()

    init {
        scope.launch {
            backupManager.state.collect { backupState ->
                mutableState.value = mutableState.value.copy(backupState = backupState)
            }
        }
    }

    override fun setBackupFolder(uri: Uri) {
        scope.launch {
            runBackupAction {
                backupManager.setBackupFolder(uri)
                migrateLegacyContactPhotos()
            }
        }
    }

    override fun setAutoExportEnabled(enabled: Boolean) {
        scope.launch {
            runBackupAction {
                backupManager.setAutoExportEnabled(enabled)
            }
        }
    }

    override fun exportBackupNow() {
        scope.launch {
            runBackupAction {
                backupManager.exportNow()
            }
        }
    }

    override fun importBackup(uri: Uri) {
        scope.launch {
            runBackupAction {
                backupManager.importFromUri(uri)
            }
        }
    }

    override fun importBackupFolder(uri: Uri) {
        scope.launch {
            runBackupAction {
                backupManager.importFromBackupFolder(uri)
            }
        }
    }

    private suspend fun runBackupAction(block: suspend () -> Unit) {
        mutableState.value = mutableState.value.copy(isBackupRunning = true)
        status.setError(null)
        runCatching { block() }
            .onFailure { error -> status.setError(error.message ?: "Backup operation failed.") }
        mutableState.value = mutableState.value.copy(isBackupRunning = false)
    }
}
