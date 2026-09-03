package com.supercontacts.app.data.backup

import android.content.Context
import android.net.Uri
import com.gernalix.personalhub.core.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** People delegates all file transfer to PersonalHub's database capsule. */
class SuperContactsBackupManager(context: Context, closeDataLayer: () -> Unit, notifyDataLayerChanged: () -> Unit) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(readState())
    val state: StateFlow<BackupState> = mutableState
    fun notifyDatabaseChanged() { HubAutoExport.request(appContext); mutableState.value = readState() }
    suspend fun setBackupFolder(treeUri: Uri) = withContext(Dispatchers.IO) { DatabaseVault.configureFolder(appContext, treeUri); mutableState.value = readState() }
    suspend fun setAutoExportEnabled(enabled: Boolean) { DatabaseNavigation.open(appContext) }
    suspend fun exportNow() = withContext(Dispatchers.IO) { DatabaseVault.exportNow(appContext); mutableState.value = readState() }
    suspend fun importFromUri(uri: Uri) { DatabaseNavigation.open(appContext) }
    suspend fun importFromBackupFolder(treeUri: Uri) { DatabaseNavigation.open(appContext) }
    private fun readState() = BackupState(folderUri = DatabaseVault.folder(appContext), folderLabel = DatabaseVault.folder(appContext), lastExportAt = DatabaseVault.lastExport(appContext).takeIf { it > 0 }, lastError = DatabaseVault.error(appContext), isConfigured = DatabaseVault.folder(appContext) != null, isAccessible = DatabaseVault.folder(appContext) != null)
    companion object { const val BACKUP_FILE_NAME = "personalhub.db" }
}
