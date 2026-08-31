package com.gernalix.personalhub.core.migration

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gernalix.personalhub.core.model.DedupeDecision
import com.gernalix.personalhub.core.model.MigrationMapping
import com.gernalix.personalhub.core.model.SourceApp
import com.gernalix.personalhub.core.model.UnifiedEntityType

class MigrationMappingStore(context: Context) {
    private val helper = Helper(context.applicationContext)

    fun record(mapping: MigrationMapping) {
        helper.writableDatabase.use { db ->
            db.insertWithOnConflict(
                TABLE_MAPPINGS,
                null,
                ContentValues().apply {
                    put("source_app", mapping.sourceApp.key)
                    put("source_table", mapping.sourceTable)
                    put("source_id", mapping.sourceId)
                    put("entity_type", mapping.entityType.key)
                    put("new_id", mapping.newId)
                    put("dedupe_decision", mapping.dedupeDecision.key)
                    put("notes", mapping.notes)
                },
                SQLiteDatabase.CONFLICT_ABORT,
            )
        }
    }

    fun mappings(): List<MigrationMappingRow> =
        helper.readableDatabase.use { db ->
            db.rawQuery(
                """
                SELECT source_app, source_table, source_id, entity_type, new_id, dedupe_decision, notes
                FROM $TABLE_MAPPINGS
                ORDER BY source_app, source_table, source_id
                """.trimIndent(),
                emptyArray(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            MigrationMappingRow(
                                sourceApp = cursor.getString(0),
                                sourceTable = cursor.getString(1),
                                sourceId = cursor.getString(2),
                                entityType = cursor.getString(3),
                                newId = cursor.getString(4),
                                dedupeDecision = cursor.getString(5),
                                notes = cursor.getString(6).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }

    fun integrityCheck(): String =
        helper.readableDatabase.use { db ->
            db.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "missing-result"
            }
        }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_MAPPINGS (
                    source_app TEXT NOT NULL,
                    source_table TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    new_id TEXT NOT NULL,
                    dedupe_decision TEXT NOT NULL,
                    notes TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(source_app, source_table, source_id)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_${TABLE_MAPPINGS}_entity ON $TABLE_MAPPINGS(entity_type, new_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion == newVersion) { "Unsupported PersonalHub migration DB upgrade $oldVersion -> $newVersion" }
        }
    }

    data class MigrationMappingRow(
        val sourceApp: String,
        val sourceTable: String,
        val sourceId: String,
        val entityType: String,
        val newId: String,
        val dedupeDecision: String,
        val notes: String,
    )

    companion object {
        const val DB_NAME = "personalhub_migration_map.db"
        const val DB_VERSION = 1
        const val TABLE_MAPPINGS = "migration_mappings"

        val expectedSourceApps: Set<String> = SourceApp.entries.mapTo(linkedSetOf()) { it.key }
        val expectedEntityTypes: Set<String> = UnifiedEntityType.entries.mapTo(linkedSetOf()) { it.key }
        val expectedDedupeDecisions: Set<String> = DedupeDecision.entries.mapTo(linkedSetOf()) { it.key }
    }
}
