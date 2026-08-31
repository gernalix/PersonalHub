package com.gernalix.sostanze.capsules.importexport

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import com.gernalix.sostanze.data.InteractionRuleEntity
import com.gernalix.sostanze.data.InteractionTargetEntity
import com.gernalix.sostanze.data.IntakeEventEntity
import com.gernalix.sostanze.data.MacroEntity
import com.gernalix.sostanze.data.MacroItemEntity
import com.gernalix.sostanze.data.NotificationStateEntity
import com.gernalix.sostanze.data.PrescriptionEntity
import com.gernalix.sostanze.data.SettingEntity
import com.gernalix.sostanze.data.SostanzeDatabase
import com.gernalix.sostanze.data.StockAdjustmentEntity
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.UtcDateCodec
import java.io.File

object SostanzeDataPorter {
    const val EXPORT_FILE_NAME = "sostanze.db"
    const val SETTING_EXPORT_TREE_URI = "export_tree_uri"

    private val requiredTables = setOf(
        "substances",
        "intake_events",
        "stock_adjustments",
        "prescriptions",
        "interaction_rules",
        "interaction_targets",
        "notification_state",
        "settings",
        "macros",
        "macro_items",
    )

    fun isoUtc(ms: Long): String = UtcDateCodec.isoUtc(ms)

    fun prescriptionIsoUtc(epochDay: Long): String =
        UtcDateCodec.prescriptionIsoUtc(epochDay)

    fun exportNow(context: Context, db: SostanzeDatabase, treeUriText: String?): ExportResult {
        if (treeUriText.isNullOrBlank()) return ExportResult.Skipped
        val treeUri = Uri.parse(treeUriText)
        return runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            val temp = File(context.cacheDir, "sostanze-export-${System.nanoTime()}.db")
            temp.delete()
            db.openHelper.writableDatabase.execSQL("VACUUM INTO '${temp.absolutePath.replace("'", "''")}'")
            validateSqlite(temp)
            writeSingleSafDatabase(context.contentResolver, treeUri, temp)
            temp.delete()
            ExportResult.Success
        }.getOrElse { error ->
            ExportResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    fun copyImportCandidate(context: Context, source: Uri): File {
        val temp = File(context.cacheDir, "sostanze-import-${System.nanoTime()}.db")
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Cannot open selected database" }
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        validateSqlite(temp)
        return temp
    }

    fun readSnapshot(file: File): PortableSnapshot {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            PortableSnapshot(
                substances = querySubstances(it),
                intakes = queryIntakes(it),
                stockAdjustments = queryStockAdjustments(it),
                prescriptions = queryPrescriptions(it),
                interactionRules = queryInteractionRules(it),
                interactionTargets = queryInteractionTargets(it),
                notifications = queryNotifications(it),
                settings = querySettings(it),
                macros = queryMacros(it),
                macroItems = queryMacroItems(it),
            )
        }
    }

    fun validateSqlite(file: File) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val actual = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) actual += cursor.getString(0)
            }
            require(actual.containsAll(requiredTables)) {
                "Incompatible schema. Missing: ${(requiredTables - actual).sorted().joinToString()}"
            }
            requiredTables.forEach { table ->
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst() }
            }
        }
    }

    private fun writeSingleSafDatabase(resolver: ContentResolver, treeUri: Uri, source: File) {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        var target: Uri? = null
        resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    if (name == EXPORT_FILE_NAME) {
                        target = docUri
                    } else {
                        DocumentsContract.deleteDocument(resolver, docUri)
                    }
                }
            }
        val outputUri = target ?: DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
            "application/vnd.sqlite3",
            EXPORT_FILE_NAME,
        ) ?: error("Cannot create SAF export file")
        resolver.openOutputStream(outputUri, "wt").use { output ->
            requireNotNull(output) { "Cannot open SAF export output" }
            source.inputStream().use { input -> input.copyTo(output) }
        }
    }

    private fun querySubstances(db: SQLiteDatabase): List<SubstanceEntity> =
        db.rawQuery("SELECT * FROM substances", null).rows {
            SubstanceEntity(
                id = long("id"),
                name = string("name"),
                type = string("type"),
                stockCurrent = double("stock_current"),
                stockUnit = string("stock_unit"),
                dosePerIntake = double("dose_per_intake"),
                doseUnit = string("dose_unit"),
                dailyFrequency = int("daily_frequency"),
                startEpochDay = long("start_epoch_day"),
                endEpochDay = nullableLong("end_epoch_day"),
                forever = bool("forever"),
                archived = bool("archived"),
                prn = bool("prn"),
            )
        }

    private fun queryIntakes(db: SQLiteDatabase): List<IntakeEventEntity> =
        db.rawQuery("SELECT * FROM intake_events", null).rows {
            IntakeEventEntity(id = long("id"), substanceId = long("substance_id"), timestampMs = long("timestamp_ms"), timestampUtc = optString("timestamp_utc"), dose = double("dose"), doseUnit = string("dose_unit"), tapGroupId = nullableString("tap_group_id"))
        }

    private fun queryStockAdjustments(db: SQLiteDatabase): List<StockAdjustmentEntity> =
        db.rawQuery("SELECT * FROM stock_adjustments", null).rows {
            StockAdjustmentEntity(id = long("id"), substanceId = long("substance_id"), timestampMs = long("timestamp_ms"), timestampUtc = optString("timestamp_utc"), delta = double("delta"), note = nullableString("note"), resultingStock = double("resulting_stock"))
        }

    private fun queryPrescriptions(db: SQLiteDatabase): List<PrescriptionEntity> =
        db.rawQuery("SELECT * FROM prescriptions", null).rows {
            PrescriptionEntity(id = long("id"), substanceId = long("substance_id"), prescriptionEpochDay = long("prescription_epoch_day"), prescriptionDateUtc = optString("prescription_date_utc"), quantityPrescribed = double("quantity_prescribed"), refillEveryMonths = int("refill_every_months"), alertRefill = bool("alert_refill"))
        }

    private fun queryInteractionRules(db: SQLiteDatabase): List<InteractionRuleEntity> =
        db.rawQuery("SELECT * FROM interaction_rules", null).rows {
            InteractionRuleEntity(id = long("id"), sourceSubstanceId = long("source_substance_id"), avoidBeforeHours = double("avoid_before_hours"), avoidAfterHours = double("avoid_after_hours"), enforcement = string("enforcement"))
        }

    private fun queryInteractionTargets(db: SQLiteDatabase): List<InteractionTargetEntity> =
        db.rawQuery("SELECT * FROM interaction_targets", null).rows {
            InteractionTargetEntity(id = long("id"), ruleId = long("rule_id"), targetSubstanceId = nullableLong("target_substance_id"), targetKind = string("target_kind"))
        }

    private fun queryNotifications(db: SQLiteDatabase): List<NotificationStateEntity> =
        db.rawQuery("SELECT * FROM notification_state", null).rows {
            NotificationStateEntity(id = long("id"), kind = string("kind"), entityId = long("entity_id"), scheduledForMs = long("scheduled_for_ms"), scheduledForUtc = optString("scheduled_for_utc"), sentAtMs = nullableLong("sent_at_ms"), sentAtUtc = nullableString("sent_at_utc"))
        }

    private fun querySettings(db: SQLiteDatabase): List<SettingEntity> =
        db.rawQuery("SELECT * FROM settings", null).rows {
            SettingEntity(key = string("key"), value = string("value"))
        }

    private fun queryMacros(db: SQLiteDatabase): List<MacroEntity> =
        db.rawQuery("SELECT * FROM macros", null).rows {
            MacroEntity(id = long("id"), name = string("name"), archived = bool("archived"))
        }

    private fun queryMacroItems(db: SQLiteDatabase): List<MacroItemEntity> =
        db.rawQuery("SELECT * FROM macro_items", null).rows {
            MacroItemEntity(id = long("id"), macroId = long("macro_id"), substanceId = long("substance_id"))
        }

    private inline fun <T> Cursor.rows(mapper: Cursor.() -> T): List<T> = use {
        buildList {
            while (it.moveToNext()) add(it.mapper())
        }
    }

    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.double(name: String) = getDouble(getColumnIndexOrThrow(name))
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.bool(name: String) = int(name) != 0
    private fun Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.optString(name: String): String {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) "" else getString(index)
    }
}

data class PortableSnapshot(
    val substances: List<SubstanceEntity>,
    val intakes: List<IntakeEventEntity>,
    val stockAdjustments: List<StockAdjustmentEntity>,
    val prescriptions: List<PrescriptionEntity>,
    val interactionRules: List<InteractionRuleEntity>,
    val interactionTargets: List<InteractionTargetEntity>,
    val notifications: List<NotificationStateEntity>,
    val settings: List<SettingEntity>,
    val macros: List<MacroEntity>,
    val macroItems: List<MacroItemEntity>,
)

sealed class ExportResult {
    data object Skipped : ExportResult()
    data object Success : ExportResult()
    data class Failure(val message: String) : ExportResult()
}
