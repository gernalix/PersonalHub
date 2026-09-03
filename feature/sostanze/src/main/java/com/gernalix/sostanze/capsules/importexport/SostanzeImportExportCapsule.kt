package com.gernalix.sostanze.capsules.importexport

import android.content.Context
import android.net.Uri
import com.gernalix.personalhub.core.database.*
import com.gernalix.sostanze.capsules.CapsuleResult
import com.gernalix.sostanze.capsules.ImportExportCapsuleApi
import com.gernalix.sostanze.data.SostanzeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SostanzeImportExportCapsule(private val context: Context, private val db: SostanzeDatabase) : ImportExportCapsuleApi {
    override suspend fun setExportDestination(treeUri: Uri): CapsuleResult = withContext(Dispatchers.IO) {
        DatabaseVault.configureFolder(context, treeUri)
        CapsuleResult.Ok
    }
    override suspend fun exportNow(): CapsuleResult = when (val result = exportStatusNow()) {
        ExportResult.Success -> CapsuleResult.Ok
        ExportResult.Skipped -> CapsuleResult.Skipped
        is ExportResult.Failure -> CapsuleResult.Error(result.message)
    }
    suspend fun exportStatusNow(): ExportResult = withContext(Dispatchers.IO) {
        runCatching { if (DatabaseVault.exportNow(context)) ExportResult.Success else ExportResult.Skipped }
            .getOrElse { ExportResult.Failure(it.message ?: it.javaClass.simpleName) }
    }
    override suspend fun importDatabase(sourceUri: Uri): CapsuleResult { DatabaseNavigation.open(context); return CapsuleResult.Skipped }
    suspend fun importFrom(source: Uri): Result<Unit> = runCatching { DatabaseNavigation.open(context) }
}
