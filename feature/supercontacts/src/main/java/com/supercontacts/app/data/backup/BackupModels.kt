package com.supercontacts.app.data.backup

data class BackupState(
    val folderUri: String? = null,
    val folderLabel: String? = null,
    val backupFileName: String = SuperContactsBackupManager.BACKUP_FILE_NAME,
    val autoExportEnabled: Boolean = true,
    val lastExportAt: Long? = null,
    val lastError: String? = null,
    val isConfigured: Boolean = false,
    val isAccessible: Boolean = false,
    val isBusy: Boolean = false,
)
