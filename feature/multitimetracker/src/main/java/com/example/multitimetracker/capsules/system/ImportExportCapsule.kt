// v471
// v462
package com.example.multitimetracker.capsules.system

import android.content.Context
import android.net.Uri
import com.example.multitimetracker.BackupFolderInspection
import kotlinx.coroutines.CoroutineScope

data class ImportExportSnapshot(
    val tasks: List<Any>,
    val tags: List<Any>,
    val closedSessions: List<Any>,
    val tagSessions: List<Any>,
    val tagParentsByChild: Map<Long, Set<Long>>,
    val lifePeriods: List<Any>,
    val timeFenceRules: List<Any>,
    val chains: List<Any>,
    val activeChainRun: Any?,
    val appUsageMs: Long,
    val quickEventTemplates: List<Any> = emptyList(),
    val quickEventEntries: List<Any> = emptyList(),
    val quickEventFieldDefinitions: List<Any> = emptyList(),
    val quickEventFieldValues: List<Any> = emptyList(),
    val quickEventMacros: List<Any> = emptyList(),
    val quickEventMacroActions: List<Any> = emptyList()
)

interface ImportExportCapsule {
    fun setBackupRootFolder(context: Context, treeUri: Uri)
    fun inspectBackupFolder(context: Context): BackupFolderInspection
    fun exportBackup(context: Context)
    fun exportCsv(context: Context)
    fun importCsv(context: Context, uris: List<Uri>, scope: CoroutineScope)
    fun importDatabaseFromUri(context: Context, uri: Uri, scope: CoroutineScope)
    fun restoreLastPreImportBackup(context: Context, scope: CoroutineScope)
    fun importBackup(context: Context, scope: CoroutineScope)
    suspend fun importBackupBlocking(context: Context): Boolean
}
