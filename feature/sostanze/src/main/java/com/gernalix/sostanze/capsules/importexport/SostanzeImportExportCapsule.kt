package com.gernalix.sostanze.capsules.importexport

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import com.gernalix.sostanze.capsules.CapsuleResult
import com.gernalix.sostanze.capsules.ImportExportCapsuleApi
import com.gernalix.sostanze.data.SettingEntity
import com.gernalix.sostanze.data.SostanzeDatabase
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.UtcDateCodec

class SostanzeImportExportCapsule(
    private val context: Context,
    private val db: SostanzeDatabase,
) : ImportExportCapsuleApi {
    private val dao = db.dao()

    override suspend fun setExportDestination(treeUri: Uri): CapsuleResult {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        dao.upsertSetting(SettingEntity(SostanzeDataPorter.SETTING_EXPORT_TREE_URI, treeUri.toString()))
        return exportNow()
    }

    override suspend fun exportNow(): CapsuleResult =
        SostanzeDataPorter.exportNow(
            context = context,
            db = db,
            treeUriText = dao.settingValue(SostanzeDataPorter.SETTING_EXPORT_TREE_URI)
        ).toCapsuleResult()

    suspend fun exportStatusNow(): ExportResult =
        SostanzeDataPorter.exportNow(
            context = context,
            db = db,
            treeUriText = dao.settingValue(SostanzeDataPorter.SETTING_EXPORT_TREE_URI)
        )

    override suspend fun importDatabase(sourceUri: Uri): CapsuleResult =
        importFrom(sourceUri).fold(
            onSuccess = { CapsuleResult.Ok },
            onFailure = { CapsuleResult.Error(it.message ?: it.javaClass.simpleName) }
        )

    suspend fun importFrom(source: Uri): Result<Unit> {
        return runCatching {
            val file = SostanzeDataPorter.copyImportCandidate(context, source)
            val imported = SostanzeDataPorter.readSnapshot(file)
            replaceAll(imported)
            file.delete()
            exportNow()
        }
    }

    private suspend fun replaceAll(imported: PortableSnapshot) {
        db.withTransaction {
            dao.deleteAllNotificationState()
            dao.deleteAllMacroItems()
            dao.deleteAllMacros()
            dao.deleteAllInteractionTargets()
            dao.deleteAllInteractionRules()
            dao.deleteAllPrescriptions()
            dao.deleteAllStockAdjustments()
            dao.deleteAllIntakes()
            dao.deleteAllSubstances()
            val exportTree = dao.settingValue(SostanzeDataPorter.SETTING_EXPORT_TREE_URI)
            dao.deleteAllSettings()
            dao.insertSubstances(imported.substances.map { it.forceMg() })
            dao.insertIntakes(
                imported.intakes.map {
                    it.copy(
                        timestampUtc = it.timestampUtc.ifBlank { UtcDateCodec.isoUtc(it.timestampMs) },
                        doseUnit = "mg"
                    )
                }
            )
            dao.insertStockAdjustments(
                imported.stockAdjustments.map {
                    it.copy(timestampUtc = it.timestampUtc.ifBlank { UtcDateCodec.isoUtc(it.timestampMs) })
                }
            )
            dao.insertPrescriptions(
                imported.prescriptions.map {
                    it.copy(prescriptionDateUtc = it.prescriptionDateUtc.ifBlank { UtcDateCodec.prescriptionIsoUtc(it.prescriptionEpochDay) })
                }
            )
            dao.insertInteractionRules(imported.interactionRules)
            dao.insertInteractionTargets(imported.interactionTargets)
            dao.insertNotificationState(
                imported.notifications.map {
                    it.copy(scheduledForUtc = it.scheduledForUtc.ifBlank { UtcDateCodec.isoUtc(it.scheduledForMs) })
                }
            )
            dao.insertSettings(imported.settings.filterNot { it.key == SostanzeDataPorter.SETTING_EXPORT_TREE_URI })
            if (!exportTree.isNullOrBlank()) {
                dao.upsertSetting(SettingEntity(SostanzeDataPorter.SETTING_EXPORT_TREE_URI, exportTree))
            }
            dao.insertMacros(imported.macros)
            dao.insertMacroItems(imported.macroItems)
        }
    }

    private fun ExportResult.toCapsuleResult(): CapsuleResult = when (this) {
        ExportResult.Success -> CapsuleResult.Ok
        ExportResult.Skipped -> CapsuleResult.Skipped
        is ExportResult.Failure -> CapsuleResult.Error(message)
    }

    private fun SubstanceEntity.forceMg(): SubstanceEntity =
        copy(stockUnit = "mg", doseUnit = "mg")
}
