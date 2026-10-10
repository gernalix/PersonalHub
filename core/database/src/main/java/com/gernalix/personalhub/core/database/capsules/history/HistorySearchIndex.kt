package com.gernalix.personalhub.core.database.capsules.history

import androidx.sqlite.db.SupportSQLiteDatabase

/** External-content FTS indexes: original events and payloads stay in their existing journals. */
object HistorySearchIndex {
    private val sources = mapOf(
        "hub_git_applied_patches" to listOf("metadata_json"),
        "mutation_events" to listOf("event_type", "before_json", "after_json", "context_json"),
        "hub_git_history_index" to listOf("display_before", "display_after", "reason", "table_name"),
        "hub_activity_log" to listOf("entity_label", "detail_value", "action"),
        "substances" to listOf("name", "canonical_name"),
        "contact_fields" to listOf("value", "description"),
        "finance_titles" to listOf("name"),
        "finance_transactions" to listOf("notes"),
        "sessions" to listOf("title"),
        "places" to listOf("nickname", "address", "notes"),
        "since_when_counters" to listOf("title", "description"),
    )
    fun indexFor(table: String): String = "hub_history_fts_$table"
    val excludedTables: Set<String> = sources.keys.flatMap { source ->
        val name = indexFor(source)
        listOf(name, "${name}_segments", "${name}_segdir", "${name}_docsize", "${name}_stat", "${name}_content")
    }.toSet()

    fun expectedSql(): Map<String, String> = buildMap {
        sources.forEach { (source, columns) ->
            val index = indexFor(source)
            val names = columns.joinToString(",")
            val values = columns.joinToString(",") { "NEW.$it" }
            put("${index}_INSERT", "CREATE TRIGGER `${index}_INSERT` AFTER INSERT ON `$source` BEGIN INSERT INTO `$index`(docid,$names) VALUES(NEW.rowid,$values); END")
            // FTS4 external content requires deletion before the source payload changes.
            put("${index}_DELETE", "CREATE TRIGGER `${index}_DELETE` BEFORE DELETE ON `$source` BEGIN DELETE FROM `$index` WHERE docid=OLD.rowid; END")
            put("${index}_UPDATE_BEFORE", "CREATE TRIGGER `${index}_UPDATE_BEFORE` BEFORE UPDATE ON `$source` BEGIN DELETE FROM `$index` WHERE docid=OLD.rowid; END")
            put("${index}_UPDATE_AFTER", "CREATE TRIGGER `${index}_UPDATE_AFTER` AFTER UPDATE ON `$source` BEGIN INSERT INTO `$index`(docid,$names) VALUES(NEW.rowid,$values); END")
        }
    }

    fun install(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            val existing = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
            sources.forEach { (source, columns) ->
                val index = indexFor(source)
                if (index !in existing) {
                    db.execSQL("CREATE VIRTUAL TABLE `$index` USING fts4(${columns.joinToString(",")},content='$source',tokenize=unicode61)")
                    db.execSQL("INSERT INTO `$index`(`$index`) VALUES('rebuild')")
                }
            }
            expectedSql().values.forEach { sql -> db.execSQL(sql.replace("CREATE TRIGGER ","CREATE TRIGGER IF NOT EXISTS ")) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Literal token-prefix query, never FTS operators supplied by the user. */
    fun matchQuery(query: String): String? = Regex("[\\p{L}\\p{N}]+").findAll(query).map { "\"${it.value}\"*" }.toList()
        .takeIf { it.isNotEmpty() }?.joinToString(" ")
}
