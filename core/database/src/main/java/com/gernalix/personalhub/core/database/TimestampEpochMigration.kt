package com.gernalix.personalhub.core.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.time.Instant

/** Converts precisely representable legacy UTC instants; any unexpected value aborts the transaction. */
internal class TimestampEpochMigration(private val context: Context) : Migration(16, 17) {
    private val columns = linkedMapOf(
        "finance_accounts" to setOf("openedAt"),
        "finance_transactions" to setOf("occurredAt", "createdAt", "updatedAt", "reminderAt"),
        "finance_transfers" to setOf("createdAt"),
        "finance_macros" to setOf("occurredAt", "createdAt"),
        "finance_recurrences" to setOf("createdAt", "updatedAt"),
        "finance_recurrence_overrides" to setOf("createdAt"),
        "finance_attachments" to setOf("createdAt"),
        "intake_events" to setOf("timestamp_utc"),
        "stock_adjustments" to setOf("timestamp_utc"),
        "notification_state" to setOf("scheduled_for_utc", "sent_at_utc"),
        "hub_entity_bindings" to setOf("updated_at"),
        "hub_context_types" to setOf("created_at", "updated_at"),
        "hub_contexts" to setOf("created_at", "updated_at"),
        "hub_resources" to setOf("createdAt", "updatedAt"),
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        val entities = JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/16.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database").getJSONArray("entities")
        val byName = (0 until entities.length()).associate { i ->
            entities.getJSONObject(i).let { it.getString("tableName") to it }
        }
        // Room owns the outer migration transaction. A failure leaves the v16 file untouched.
        for ((table, instants) in columns) {
            val entity = requireNotNull(byName[table])
            val newTable = "${table}_epoch_new"
            val fields = entity.getJSONArray("fields")
            var ddl = entity.getString("createSql").replace("\${TABLE_NAME}", newTable)
            for (column in instants) {
                val definition = Regex("(`${Regex.escape(column)}`\\s+)TEXT\\b")
                require(definition.containsMatchIn(ddl)) { "Missing v16 timestamp column $table.$column" }
                ddl = ddl.replace(definition, "$1INTEGER")
                if (column in setOf("timestamp_utc", "scheduled_for_utc")) {
                    ddl = ddl.replace(Regex("(`${Regex.escape(column)}`\\s+INTEGER NOT NULL DEFAULT )''"), "$10")
                }
            }
            db.execSQL(ddl)
            val names = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
            db.query("SELECT * FROM `$table`").use { old ->
                while (old.moveToNext()) {
                    val values = ContentValues(names.size)
                    for ((index, name) in names.withIndex()) {
                        if (old.isNull(index)) {
                            values.putNull(name)
                        } else if (name in instants) {
                            val raw = old.getString(index)
                            val companion = when (name) {
                                "timestamp_utc" -> "timestamp_ms"
                                "scheduled_for_utc" -> "scheduled_for_ms"
                                "sent_at_utc" -> "sent_at_ms"
                                else -> null
                            }
                            val epoch = if (raw.isEmpty() && companion != null) {
                                val companionIndex = old.getColumnIndexOrThrow(companion)
                                require(!old.isNull(companionIndex)) { "Missing $table.$companion" }
                                old.getLong(companionIndex)
                            } else exactEpochMs(raw)
                            values.put(name, epoch)
                        } else when (old.getType(index)) {
                            Cursor.FIELD_TYPE_INTEGER -> values.put(name, old.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> values.put(name, old.getDouble(index))
                            Cursor.FIELD_TYPE_BLOB -> values.put(name, old.getBlob(index))
                            else -> values.put(name, old.getString(index))
                        }
                    }
                    require(db.insert(newTable, SQLiteDatabase.CONFLICT_ABORT, values) != -1L)
                }
            }
            db.execSQL("DROP TABLE `$table`")
            db.execSQL("ALTER TABLE `$newTable` RENAME TO `$table`")
            val indices = entity.optJSONArray("indices")
            if (indices != null) for (i in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
    }

    companion object {
        fun exactEpochMs(raw: String): Long {
            require(raw.isNotBlank()) { "Empty legacy instant" }
            val epoch = raw.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.parse(raw)
            val ms = epoch.toEpochMilli()
            require(Instant.ofEpochMilli(ms) == epoch) { "Sub-millisecond legacy instant" }
            return ms
        }
    }
}
