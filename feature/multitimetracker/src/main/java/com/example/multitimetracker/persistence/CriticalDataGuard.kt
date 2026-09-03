package com.example.multitimetracker.persistence

import android.content.Context
import com.gernalix.personalhub.core.database.LegacyDatabase as SQLiteDatabase
import org.json.JSONObject
import java.io.File

data class CriticalDataCounts(
    val sessions: Long = 0L,
    val sessionTags: Long = 0L,
    val tasks: Long = 0L,
    val tags: Long = 0L,
    val closedSessions: Long = 0L,
    val tagSessions: Long = 0L,
    val timeFenceRules: Long = 0L,
    val tagParents: Long = 0L,
    val lifePeriods: Long = 0L,
    val chains: Long = 0L,
    val quickEventTemplates: Long = 0L,
    val quickEventEntries: Long = 0L,
    val quickEventFields: Long = 0L,
    val quickEventFieldValues: Long = 0L,
    val quickEventMacros: Long = 0L,
    val quickEventMacroActions: Long = 0L,
    val settings: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sessions", sessions)
        .put("sessionTags", sessionTags)
        .put("tasks", tasks)
        .put("tags", tags)
        .put("closedSessions", closedSessions)
        .put("tagSessions", tagSessions)
        .put("timeFenceRules", timeFenceRules)
        .put("tagParents", tagParents)
        .put("lifePeriods", lifePeriods)
        .put("chains", chains)
        .put("quickEventTemplates", quickEventTemplates)
        .put("quickEventEntries", quickEventEntries)
        .put("quickEventFields", quickEventFields)
        .put("quickEventFieldValues", quickEventFieldValues)
        .put("quickEventMacros", quickEventMacros)
        .put("quickEventMacroActions", quickEventMacroActions)
        .put("settings", settings)

    companion object {
        fun fromJson(obj: JSONObject): CriticalDataCounts = CriticalDataCounts(
            sessions = obj.optLong("sessions", 0L),
            sessionTags = obj.optLong("sessionTags", 0L),
            tasks = obj.optLong("tasks", 0L),
            tags = obj.optLong("tags", 0L),
            closedSessions = obj.optLong("closedSessions", 0L),
            tagSessions = obj.optLong("tagSessions", 0L),
            timeFenceRules = obj.optLong("timeFenceRules", 0L),
            tagParents = obj.optLong("tagParents", 0L),
            lifePeriods = obj.optLong("lifePeriods", 0L),
            chains = obj.optLong("chains", 0L),
            quickEventTemplates = obj.optLong("quickEventTemplates", 0L),
            quickEventEntries = obj.optLong("quickEventEntries", 0L),
            quickEventFields = obj.optLong("quickEventFields", 0L),
            quickEventFieldValues = obj.optLong("quickEventFieldValues", 0L),
            quickEventMacros = obj.optLong("quickEventMacros", 0L),
            quickEventMacroActions = obj.optLong("quickEventMacroActions", 0L),
            settings = obj.optLong("settings", 0L),
        )
    }
}

object CriticalDataGuard {
    private const val PREFS = "critical_data_guard"
    private const val KEY_LAST_COUNTS = "last_counts_json"

    fun fromSnapshotJson(json: String?): CriticalDataCounts {
        if (json.isNullOrBlank()) return CriticalDataCounts()
        val root = JSONObject(json)
        return CriticalDataCounts(
            tasks = root.optJSONArray("tasks")?.length()?.toLong() ?: 0L,
            tags = root.optJSONArray("tags")?.length()?.toLong() ?: 0L,
            closedSessions = root.optJSONArray("closedSessions")?.length()?.toLong() ?: 0L,
            tagSessions = root.optJSONArray("tagSessions")?.length()?.toLong() ?: 0L,
            timeFenceRules = root.optJSONArray("timeFenceRules")?.length()?.toLong() ?: 0L,
            tagParents = root.optJSONArray("tagParents")?.length()?.toLong() ?: 0L,
            lifePeriods = root.optJSONArray("lifePeriods")?.length()?.toLong() ?: 0L,
            chains = root.optJSONArray("chains")?.length()?.toLong() ?: 0L,
            quickEventTemplates = root.optJSONArray("quickEventTemplates")?.length()?.toLong() ?: 0L,
            quickEventEntries = root.optJSONArray("quickEventEntries")?.length()?.toLong() ?: 0L,
            quickEventFields = root.optJSONArray("quickEventFieldDefinitions")?.length()?.toLong() ?: 0L,
            quickEventFieldValues = root.optJSONArray("quickEventFieldValues")?.length()?.toLong() ?: 0L,
            quickEventMacros = root.optJSONArray("quickEventMacros")?.length()?.toLong() ?: 0L,
            quickEventMacroActions = root.optJSONArray("quickEventMacroActions")?.length()?.toLong() ?: 0L,
        )
    }

    fun readInternal(context: Context): CriticalDataCounts {
        val db = SnapshotSqlite.openReadableDb(context)
        return try {
            readOpenDb(db)
        } finally {
            db.close()
        }
    }

    fun readFile(dbFile: File): CriticalDataCounts {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            readOpenDb(db)
        } finally {
            db.close()
        }
    }

    fun readOpenDb(db: SQLiteDatabase): CriticalDataCounts {
        fun hasTable(table: String): Boolean {
            return db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(table)
            ).use { it.moveToFirst() }
        }
        fun count(table: String, where: String? = null): Long {
            if (!hasTable(table)) return 0L
            val sql = if (where == null) "SELECT COUNT(*) FROM $table" else "SELECT COUNT(*) FROM $table WHERE $where"
            return db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
        fun countMeaningfulSettings(): Long {
            if (!hasTable("ui_prefs_mirror")) return 0L
            val json = db.rawQuery("SELECT json FROM ui_prefs_mirror WHERE id=1 LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (json.isNullOrBlank()) return 0L
            val obj = JSONObject(json)
            val keys = obj.keys()
            var count = 0L
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == UiPrefsStore.KEY_LAST_AUTOCONSIST_PATCH) continue
                count += 1L
            }
            return count
        }

        val snapshotJson = db.rawQuery("SELECT json FROM snapshot WHERE id=1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val snapshotCounts = fromSnapshotJson(snapshotJson)
        return snapshotCounts.copy(
            sessions = count(SnapshotSqlite.SESSIONS_TABLE, "deleted_at_ms IS NULL"),
            sessionTags = count(SnapshotSqlite.SESSION_TAGS_TABLE),
            quickEventTemplates = maxOf(snapshotCounts.quickEventTemplates, count(SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE, "deleted_at_ms IS NULL")),
            quickEventEntries = maxOf(snapshotCounts.quickEventEntries, count(SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE, "deleted_at_ms IS NULL")),
            quickEventFields = maxOf(snapshotCounts.quickEventFields, count(SnapshotSqlite.QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "deleted_at_ms IS NULL")),
            quickEventFieldValues = maxOf(snapshotCounts.quickEventFieldValues, count(SnapshotSqlite.QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE)),
            quickEventMacros = maxOf(snapshotCounts.quickEventMacros, count(SnapshotSqlite.QUICK_EVENT_MACROS_TABLE, "deleted_at_ms IS NULL")),
            quickEventMacroActions = maxOf(snapshotCounts.quickEventMacroActions, count(SnapshotSqlite.QUICK_EVENT_MACRO_ACTIONS_TABLE)),
            settings = countMeaningfulSettings(),
        )
    }

    fun criticalDrops(
        before: CriticalDataCounts,
        after: CriticalDataCounts,
        includeLegacyTasks: Boolean = true,
    ): List<String> {
        val drops = ArrayList<String>()
        fun check(name: String, old: Long, new: Long) {
            if (old > 0L && new == 0L) drops += "$name: $old -> 0"
        }
        check("sessions", before.sessions, after.sessions)
        check("sessionTags", before.sessionTags, after.sessionTags)
        if (includeLegacyTasks) {
            check("tasks", before.tasks, after.tasks)
        }
        check("tags", before.tags, after.tags)
        check("closedSessions", before.closedSessions, after.closedSessions)
        check("tagSessions", before.tagSessions, after.tagSessions)
        check("timeFenceRules", before.timeFenceRules, after.timeFenceRules)
        check("tagParents", before.tagParents, after.tagParents)
        check("lifePeriods", before.lifePeriods, after.lifePeriods)
        check("chains", before.chains, after.chains)
        check("quickEventTemplates", before.quickEventTemplates, after.quickEventTemplates)
        check("quickEventEntries", before.quickEventEntries, after.quickEventEntries)
        check("quickEventFields", before.quickEventFields, after.quickEventFields)
        check("quickEventFieldValues", before.quickEventFieldValues, after.quickEventFieldValues)
        check("quickEventMacros", before.quickEventMacros, after.quickEventMacros)
        check("quickEventMacroActions", before.quickEventMacroActions, after.quickEventMacroActions)
        check("settings", before.settings, after.settings)
        return drops
    }

    fun requireNoCriticalDrop(
        context: Context,
        component: String,
        action: String,
        before: CriticalDataCounts,
        after: CriticalDataCounts,
        sourceFile: String? = null,
        destFile: String? = null,
        includeLegacyTasks: Boolean = true,
    ) {
        val drops = criticalDrops(
            before = before,
            after = after,
            includeLegacyTasks = includeLegacyTasks,
        )
        if (drops.isEmpty()) return
        ForensicLog.record(
            context = context,
            component = component,
            action = "${action}_critical_drop_blocked",
            sourceFile = sourceFile,
            destFile = destFile,
            beforeCounts = before,
            counts = after,
            details = JSONObject()
                .put("includeLegacyTasks", includeLegacyTasks)
                .put("phase", action),
            error = drops.joinToString("; ")
        )
        throw IllegalStateException("Critical persistent data loss blocked: ${drops.joinToString("; ")}")
    }

    fun rememberLastGood(context: Context, counts: CriticalDataCounts) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_COUNTS, counts.toJson().toString())
            .apply()
    }

    fun lastGood(context: Context): CriticalDataCounts? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_COUNTS, null)
            ?: return null
        return CriticalDataCounts.fromJson(JSONObject(raw))
    }
}
