// v459
package com.example.multitimetracker.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.multitimetracker.util.CapsuleAudit
import com.example.multitimetracker.util.CapsuleWriteApi

/**
 * SQLite storage for the authoritative snapshot.
 *
 * We store the same JSON blob already used by SnapshotStore as a single-row table.
 * This minimizes migration risk while making persistence transactional.
 */
object SnapshotSqlite {

    internal const val DB_NAME = "multitimer.db"
    internal const val DB_VERSION = 10
    private const val TAG = "SnapshotSqlite"
    private const val SCHEMA_GUARD_PREFS = "snapshot_sqlite_schema_guard"
    private const val STARTUP_SCHEMA_GUARD_KEY = "startup_schema_guard_v2_db$DB_VERSION"
    private const val STARTUP_SESSION_SCHEMA_GUARD_KEY = "startup_session_schema_guard_v1_db$DB_VERSION"
    private const val STARTUP_QUICK_EVENT_SCHEMA_GUARD_KEY = "startup_quick_event_schema_guard_v1_db$DB_VERSION"

    private const val TABLE = "snapshot"
    private const val HISTORY_TABLE = "snapshot_history"
    private const val HISTORY_PAYLOAD_TABLE = "snapshot_payloads"
    private const val HISTORY_KIND_LEGACY = "legacy_snapshot"
    private const val HISTORY_KIND_CHECKPOINT = "checkpoint"
    private const val HISTORY_KIND_DELTA = "delta"
    private const val LEGACY_COMPACTION_BATCH_ROWS = 80
    private const val MAX_SNAPSHOT_HISTORY_ROWS = 720
    private const val MAX_AUDIT_EVENT_ROWS = 1500
    private const val MIN_VACUUM_RECLAIM_BYTES = 8L * 1024L * 1024L

    internal const val AUDIT_TABLE = "audit_events"

    // Session-only scaffolding (not yet used by the app logic).
    internal const val SESSIONS_TABLE = "sessions"
    internal const val SESSION_TAGS_TABLE = "session_tags"
    internal const val QUICK_EVENT_TEMPLATES_TABLE = "quick_event_templates"
    internal const val QUICK_EVENT_TEMPLATE_TAGS_TABLE = "quick_event_template_tags"
    internal const val QUICK_EVENT_ENTRIES_TABLE = "quick_event_entries"
    internal const val QUICK_EVENT_ENTRY_TAGS_TABLE = "quick_event_entry_tags"
    internal const val QUICK_EVENT_TEMPLATE_FIELDS_TABLE = "quick_event_template_fields"
    internal const val QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE = "quick_event_entry_field_values"
    internal const val QUICK_EVENT_MACROS_TABLE = "quick_event_macros"
    internal const val QUICK_EVENT_MACRO_TAGS_TABLE = "quick_event_macro_tags"
    internal const val QUICK_EVENT_MACRO_ACTIONS_TABLE = "quick_event_macro_actions"

    @Volatile
    private var sessionSchemaEnsuredInProcess = false

    @Volatile
    private var quickEventSchemaEnsuredInProcess = false

    fun invalidateSchemaEnsureCache(context: Context) {
        sessionSchemaEnsuredInProcess = false
        quickEventSchemaEnsuredInProcess = false
        context.applicationContext
            .getSharedPreferences(SCHEMA_GUARD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(STARTUP_SCHEMA_GUARD_KEY)
            .remove(STARTUP_SESSION_SCHEMA_GUARD_KEY)
            .remove(STARTUP_QUICK_EVENT_SCHEMA_GUARD_KEY)
            .apply()
    }

    fun ensureStartupSchemas(context: Context): Boolean {
        return ensureStartupSessionSchema(context) or ensureStartupQuickEventSchema(context)
    }

    fun ensureStartupSessionSchema(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(SCHEMA_GUARD_PREFS, Context.MODE_PRIVATE)
        if (
            prefs.getBoolean(STARTUP_SCHEMA_GUARD_KEY, false) ||
            prefs.getBoolean(STARTUP_SESSION_SCHEMA_GUARD_KEY, false)
        ) {
            sessionSchemaEnsuredInProcess = true
            return false
        }
        val changed = ensureSessionTables(appContext)
        prefs.edit().putBoolean(STARTUP_SESSION_SCHEMA_GUARD_KEY, true).apply()
        return changed
    }

    fun ensureStartupQuickEventSchema(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(SCHEMA_GUARD_PREFS, Context.MODE_PRIVATE)
        if (
            prefs.getBoolean(STARTUP_SCHEMA_GUARD_KEY, false) ||
            prefs.getBoolean(STARTUP_QUICK_EVENT_SCHEMA_GUARD_KEY, false)
        ) {
            quickEventSchemaEnsuredInProcess = true
            return false
        }
        val changed = ensureQuickEventTables(appContext)
        prefs.edit().putBoolean(STARTUP_QUICK_EVENT_SCHEMA_GUARD_KEY, true).apply()
        return changed
    }

    /**
     * Clears the authoritative snapshot row (id=1).
     *
     * Used as a last-resort recovery when the JSON blob is corrupted and prevents app startup.
     */
    fun clearSnapshot(context: Context) {
        val db = Helper(context.applicationContext).writableDatabase
        try {
            db.delete(TABLE, "id = 1", null)
        } finally {
            db.close()
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            createBaseSchema(db)

            // Session-only target tables (kept alongside the snapshot JSON for a safe gradual migration).
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $SESSIONS_TABLE (
                  id INTEGER PRIMARY KEY,
                  title TEXT NOT NULL,
                  start_ms INTEGER NOT NULL,
                  end_ms INTEGER,
                  expected_end_ms INTEGER,
                  created_at_ms INTEGER NOT NULL,
                  updated_at_ms INTEGER NOT NULL,
                  deleted_at_ms INTEGER
                );
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $SESSION_TAGS_TABLE (
                  session_id INTEGER NOT NULL,
                  tag_id INTEGER NOT NULL,
                  PRIMARY KEY(session_id, tag_id)
                );
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${SESSION_TAGS_TABLE}_tag ON $SESSION_TAGS_TABLE(tag_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${SESSION_TAGS_TABLE}_session ON $SESSION_TAGS_TABLE(session_id);")
            createSessionIndexes(db)
            createQuickEventSchema(db)
            createExportUtcViews(db)

        }

       

 override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $AUDIT_TABLE (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      ts_ms INTEGER NOT NULL,
                      is_system INTEGER NOT NULL DEFAULT 0,
                      action TEXT NOT NULL,
                      entity_type TEXT,
                      entity_id INTEGER,
                      summary TEXT NOT NULL,
                      payload_json TEXT,
                      undone_at_ms INTEGER
                    );
                    """.trimIndent()
                )
            }

            if (oldVersion < 3) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $SESSIONS_TABLE (
                      id INTEGER PRIMARY KEY,
                      title TEXT NOT NULL,
                      start_ms INTEGER NOT NULL,
                      end_ms INTEGER,
                      expected_end_ms INTEGER,
                      created_at_ms INTEGER NOT NULL,
                      updated_at_ms INTEGER NOT NULL,
                      deleted_at_ms INTEGER
                    );
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $SESSION_TAGS_TABLE (
                      session_id INTEGER NOT NULL,
                      tag_id INTEGER NOT NULL,
                      PRIMARY KEY(session_id, tag_id)
                    );
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_${SESSION_TAGS_TABLE}_tag ON $SESSION_TAGS_TABLE(tag_id);")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_${SESSION_TAGS_TABLE}_session ON $SESSION_TAGS_TABLE(session_id);")
                createSessionIndexes(db)

            // v314: import/export integrity counters (single-row)
            IntegrityStatsSqlite.ensureTable(db)
            }
            if (oldVersion < 4) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ui_prefs_mirror (
                      id INTEGER PRIMARY KEY CHECK(id = 1),
                      json TEXT NOT NULL,
                      saved_at_ms INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
            }


            if (oldVersion < 5) {
                // v314: import/export integrity counters (single-row)
                IntegrityStatsSqlite.ensureTable(db)
            }
            if (oldVersion < 6) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $HISTORY_TABLE (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      json TEXT NOT NULL,
                      saved_at_ms INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_${HISTORY_TABLE}_saved_at_ms ON $HISTORY_TABLE(saved_at_ms DESC);")
            }
            if (oldVersion < 7) {
                runCatching {
                    db.execSQL("ALTER TABLE $SESSIONS_TABLE ADD COLUMN expected_end_ms INTEGER")
                }
            }
            if (oldVersion < 8) {
                ensureSnapshotHistorySchema(db)
            }
            if (oldVersion < 9) {
                createQuickEventSchema(db)
            }
            if (oldVersion < 10) {
                createQuickEventSchema(db)
            }

        }
    }

    private fun helper(context: Context): Helper = Helper(context.applicationContext)

    internal fun createBaseSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
              id INTEGER PRIMARY KEY CHECK(id = 1),
              json TEXT NOT NULL,
              saved_at_ms INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $HISTORY_TABLE (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              json TEXT NOT NULL,
              saved_at_ms INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${HISTORY_TABLE}_saved_at_ms ON $HISTORY_TABLE(saved_at_ms DESC);")
        ensureSnapshotHistorySchema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $AUDIT_TABLE (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts_ms INTEGER NOT NULL,
              is_system INTEGER NOT NULL DEFAULT 0,
              action TEXT NOT NULL,
              entity_type TEXT,
              entity_id INTEGER,
              summary TEXT NOT NULL,
              payload_json TEXT,
              undone_at_ms INTEGER
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ui_prefs_mirror (
              id INTEGER PRIMARY KEY CHECK(id = 1),
              json TEXT NOT NULL,
              saved_at_ms INTEGER NOT NULL
            );
            """.trimIndent()
        )
        IntegrityStatsSqlite.ensureTable(db)
        createAuditIndexes(db)
        createQuickEventSchema(db)
        createExportUtcViews(db)
    }

    private fun createSessionIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${SESSIONS_TABLE}_timeline ON $SESSIONS_TABLE(deleted_at_ms, start_ms DESC, id DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${SESSIONS_TABLE}_running ON $SESSIONS_TABLE(deleted_at_ms, end_ms, start_ms DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${SESSIONS_TABLE}_updated ON $SESSIONS_TABLE(updated_at_ms DESC, id DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${SESSIONS_TABLE}_visible_id ON $SESSIONS_TABLE(deleted_at_ms, id DESC);")
    }

    private fun createAuditIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${AUDIT_TABLE}_ts ON $AUDIT_TABLE(ts_ms DESC, id DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${AUDIT_TABLE}_action_ts ON $AUDIT_TABLE(action, ts_ms DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${AUDIT_TABLE}_visible_id ON $AUDIT_TABLE(is_system, undone_at_ms, id DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${AUDIT_TABLE}_entity_undo_id ON $AUDIT_TABLE(entity_type, entity_id, undone_at_ms, id DESC);")
    }

    internal fun createQuickEventSchema(db: SQLiteDatabase) {
        createQuickEventTables(db)
        ensureQuickEventColumns(db)
        createQuickEventIndexes(db)
        createExportUtcViews(db)
    }

    internal fun createExportUtcViews(db: SQLiteDatabase) {
        fun utc(column: String, alias: String): String =
            "CASE WHEN $column IS NULL THEN NULL ELSE strftime('%Y-%m-%dT%H:%M:%fZ', $column / 1000.0, 'unixepoch') END AS $alias"

        fun create(sql: String) {
            runCatching { db.execSQL(sql) }
        }

        create(
            """
            CREATE VIEW IF NOT EXISTS export_snapshot_utc_z AS
            SELECT id, json, saved_at_ms, ${utc("saved_at_ms", "saved_at_utc_z")}
            FROM snapshot;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_snapshot_history_utc_z AS
            SELECT id, json, saved_at_ms, ${utc("saved_at_ms", "saved_at_utc_z")},
                   kind, payload_id, base_history_id, delta_json, payload_hash
            FROM snapshot_history;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_snapshot_payloads_utc_z AS
            SELECT id, hash, json, created_at_ms, ${utc("created_at_ms", "created_at_utc_z")}, size_bytes
            FROM snapshot_payloads;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_audit_events_utc_z AS
            SELECT id, ts_ms, ${utc("ts_ms", "ts_utc_z")},
                   is_system, action, entity_type, entity_id, summary, payload_json,
                   undone_at_ms, ${utc("undone_at_ms", "undone_at_utc_z")}
            FROM audit_events;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_ui_prefs_mirror_utc_z AS
            SELECT id, json, saved_at_ms, ${utc("saved_at_ms", "saved_at_utc_z")}
            FROM ui_prefs_mirror;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_integrity_stats_utc_z AS
            SELECT id, json, computed_at_ms, ${utc("computed_at_ms", "computed_at_utc_z")}
            FROM integrity_stats;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_sessions_utc_z AS
            SELECT id, title,
                   start_ms, ${utc("start_ms", "start_utc_z")},
                   end_ms, ${utc("end_ms", "end_utc_z")},
                   expected_end_ms, ${utc("expected_end_ms", "expected_end_utc_z")},
                   created_at_ms, ${utc("created_at_ms", "created_at_utc_z")},
                   updated_at_ms, ${utc("updated_at_ms", "updated_at_utc_z")},
                   deleted_at_ms, ${utc("deleted_at_ms", "deleted_at_utc_z")}
            FROM sessions;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_quick_event_templates_utc_z AS
            SELECT id, title, sort_order, is_archived,
                   created_at_ms, ${utc("created_at_ms", "created_at_utc_z")},
                   updated_at_ms, ${utc("updated_at_ms", "updated_at_utc_z")},
                   deleted_at_ms, ${utc("deleted_at_ms", "deleted_at_utc_z")}
            FROM quick_event_templates;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_quick_event_entries_utc_z AS
            SELECT id, template_id, macro_id, title,
                   timestamp_ms, ${utc("timestamp_ms", "timestamp_utc_z")},
                   created_at_ms, ${utc("created_at_ms", "created_at_utc_z")},
                   updated_at_ms, ${utc("updated_at_ms", "updated_at_utc_z")},
                   deleted_at_ms, ${utc("deleted_at_ms", "deleted_at_utc_z")}
            FROM quick_event_entries;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_quick_event_template_fields_utc_z AS
            SELECT id, template_id, label, type, required, default_value, choice_options_json, display_order,
                   created_at_ms, ${utc("created_at_ms", "created_at_utc_z")},
                   updated_at_ms, ${utc("updated_at_ms", "updated_at_utc_z")},
                   deleted_at_ms, ${utc("deleted_at_ms", "deleted_at_utc_z")}
            FROM quick_event_template_fields;
            """.trimIndent()
        )
        create(
            """
            CREATE VIEW IF NOT EXISTS export_quick_event_macros_utc_z AS
            SELECT id, title, sort_order, is_archived,
                   created_at_ms, ${utc("created_at_ms", "created_at_utc_z")},
                   updated_at_ms, ${utc("updated_at_ms", "updated_at_utc_z")},
                   deleted_at_ms, ${utc("deleted_at_ms", "deleted_at_utc_z")}
            FROM quick_event_macros;
            """.trimIndent()
        )
    }

    private fun createQuickEventTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_TEMPLATES_TABLE (
              id INTEGER PRIMARY KEY,
              title TEXT NOT NULL,
              sort_order INTEGER NOT NULL DEFAULT 0,
              is_archived INTEGER NOT NULL DEFAULT 0,
              created_at_ms INTEGER NOT NULL,
              updated_at_ms INTEGER NOT NULL,
              deleted_at_ms INTEGER
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_TEMPLATE_TAGS_TABLE (
              template_id INTEGER NOT NULL,
              tag_id INTEGER NOT NULL,
              PRIMARY KEY(template_id, tag_id)
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_ENTRIES_TABLE (
              id INTEGER PRIMARY KEY,
              template_id INTEGER,
              macro_id INTEGER,
              title TEXT NOT NULL,
              timestamp_ms INTEGER NOT NULL,
              created_at_ms INTEGER NOT NULL,
              updated_at_ms INTEGER NOT NULL,
              deleted_at_ms INTEGER
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_ENTRY_TAGS_TABLE (
              entry_id INTEGER NOT NULL,
              tag_id INTEGER NOT NULL,
              PRIMARY KEY(entry_id, tag_id)
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_TEMPLATE_FIELDS_TABLE (
              id INTEGER PRIMARY KEY,
              template_id INTEGER NOT NULL,
              label TEXT NOT NULL,
              type TEXT NOT NULL DEFAULT 'TEXT',
              required INTEGER NOT NULL DEFAULT 0,
              default_value TEXT NOT NULL DEFAULT '',
              choice_options_json TEXT NOT NULL DEFAULT '[]',
              display_order INTEGER NOT NULL DEFAULT 0,
              created_at_ms INTEGER NOT NULL,
              updated_at_ms INTEGER NOT NULL,
              deleted_at_ms INTEGER
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE (
              id INTEGER PRIMARY KEY,
              entry_id INTEGER NOT NULL,
              field_id INTEGER NOT NULL,
              label TEXT NOT NULL,
              type TEXT NOT NULL DEFAULT 'TEXT',
              value TEXT NOT NULL DEFAULT '',
              display_order INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_MACROS_TABLE (
              id INTEGER PRIMARY KEY,
              title TEXT NOT NULL,
              sort_order INTEGER NOT NULL DEFAULT 0,
              is_archived INTEGER NOT NULL DEFAULT 0,
              created_at_ms INTEGER NOT NULL,
              updated_at_ms INTEGER NOT NULL,
              deleted_at_ms INTEGER
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_MACRO_TAGS_TABLE (
              macro_id INTEGER NOT NULL,
              tag_id INTEGER NOT NULL,
              PRIMARY KEY(macro_id, tag_id)
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUICK_EVENT_MACRO_ACTIONS_TABLE (
              macro_id INTEGER NOT NULL,
              template_id INTEGER NOT NULL,
              display_order INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(macro_id, template_id)
            );
            """.trimIndent()
        )
    }

    private fun createQuickEventIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_TEMPLATES_TABLE}_sort ON $QUICK_EVENT_TEMPLATES_TABLE(sort_order, title);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_TEMPLATES_TABLE}_visible_sort ON $QUICK_EVENT_TEMPLATES_TABLE(deleted_at_ms, is_archived, sort_order, title, id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_TEMPLATE_TAGS_TABLE}_tag ON $QUICK_EVENT_TEMPLATE_TAGS_TABLE(tag_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_TEMPLATE_TAGS_TABLE}_template ON $QUICK_EVENT_TEMPLATE_TAGS_TABLE(template_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_ENTRIES_TABLE}_timestamp ON $QUICK_EVENT_ENTRIES_TABLE(timestamp_ms DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_ENTRIES_TABLE}_visible_timestamp ON $QUICK_EVENT_ENTRIES_TABLE(deleted_at_ms, timestamp_ms DESC, id DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_ENTRIES_TABLE}_template ON $QUICK_EVENT_ENTRIES_TABLE(template_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_ENTRIES_TABLE}_macro ON $QUICK_EVENT_ENTRIES_TABLE(macro_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_ENTRY_TAGS_TABLE}_tag ON $QUICK_EVENT_ENTRY_TAGS_TABLE(tag_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_ENTRY_TAGS_TABLE}_entry ON $QUICK_EVENT_ENTRY_TAGS_TABLE(entry_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_TEMPLATE_FIELDS_TABLE}_template ON $QUICK_EVENT_TEMPLATE_FIELDS_TABLE(template_id, display_order);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE}_entry ON $QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE(entry_id, display_order);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_MACROS_TABLE}_sort ON $QUICK_EVENT_MACROS_TABLE(sort_order, title);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_MACROS_TABLE}_visible_sort ON $QUICK_EVENT_MACROS_TABLE(deleted_at_ms, is_archived, sort_order, title, id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_MACRO_TAGS_TABLE}_tag ON $QUICK_EVENT_MACRO_TAGS_TABLE(tag_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_MACRO_TAGS_TABLE}_macro ON $QUICK_EVENT_MACRO_TAGS_TABLE(macro_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_MACRO_ACTIONS_TABLE}_macro ON $QUICK_EVENT_MACRO_ACTIONS_TABLE(macro_id, display_order);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${QUICK_EVENT_MACRO_ACTIONS_TABLE}_template ON $QUICK_EVENT_MACRO_ACTIONS_TABLE(template_id);")
    }

    private fun ensureQuickEventColumns(
        db: SQLiteDatabase,
        onChanged: (() -> Unit)? = null
    ) {
        fun hasColumn(table: String, column: String): Boolean {
            return db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    val n = if (nameIdx >= 0) c.getString(nameIdx) else c.getString(1)
                    if (n == column) return@use true
                }
                false
            }
        }

        fun addColumnIfMissing(table: String, column: String, ddl: String) {
            if (!hasColumn(table, column)) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $ddl")
                onChanged?.invoke()
            }
        }

        addColumnIfMissing(QUICK_EVENT_TEMPLATES_TABLE, "title", "title TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(QUICK_EVENT_TEMPLATES_TABLE, "sort_order", "sort_order INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATES_TABLE, "is_archived", "is_archived INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATES_TABLE, "created_at_ms", "created_at_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATES_TABLE, "updated_at_ms", "updated_at_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATES_TABLE, "deleted_at_ms", "deleted_at_ms INTEGER")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_TAGS_TABLE, "template_id", "template_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_TAGS_TABLE, "tag_id", "tag_id INTEGER NOT NULL DEFAULT 0")

        addColumnIfMissing(QUICK_EVENT_ENTRIES_TABLE, "template_id", "template_id INTEGER")
        addColumnIfMissing(QUICK_EVENT_ENTRIES_TABLE, "macro_id", "macro_id INTEGER")
        addColumnIfMissing(QUICK_EVENT_ENTRIES_TABLE, "title", "title TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(QUICK_EVENT_ENTRIES_TABLE, "timestamp_ms", "timestamp_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_ENTRIES_TABLE, "created_at_ms", "created_at_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_ENTRIES_TABLE, "updated_at_ms", "updated_at_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_ENTRIES_TABLE, "deleted_at_ms", "deleted_at_ms INTEGER")
        addColumnIfMissing(QUICK_EVENT_ENTRY_TAGS_TABLE, "entry_id", "entry_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_ENTRY_TAGS_TABLE, "tag_id", "tag_id INTEGER NOT NULL DEFAULT 0")

        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "template_id", "template_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "label", "label TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "type", "type TEXT NOT NULL DEFAULT 'TEXT'")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "required", "required INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "default_value", "default_value TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "choice_options_json", "choice_options_json TEXT NOT NULL DEFAULT '[]'")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "display_order", "display_order INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "created_at_ms", "created_at_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "updated_at_ms", "updated_at_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "deleted_at_ms", "deleted_at_ms INTEGER")

        addColumnIfMissing(QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE, "entry_id", "entry_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE, "field_id", "field_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE, "label", "label TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE, "type", "type TEXT NOT NULL DEFAULT 'TEXT'")
        addColumnIfMissing(QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE, "value", "value TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE, "display_order", "display_order INTEGER NOT NULL DEFAULT 0")

        addColumnIfMissing(QUICK_EVENT_MACROS_TABLE, "title", "title TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(QUICK_EVENT_MACROS_TABLE, "sort_order", "sort_order INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_MACROS_TABLE, "is_archived", "is_archived INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_MACROS_TABLE, "created_at_ms", "created_at_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_MACROS_TABLE, "updated_at_ms", "updated_at_ms INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_MACROS_TABLE, "deleted_at_ms", "deleted_at_ms INTEGER")
        addColumnIfMissing(QUICK_EVENT_MACRO_TAGS_TABLE, "macro_id", "macro_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_MACRO_TAGS_TABLE, "tag_id", "tag_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_MACRO_ACTIONS_TABLE, "macro_id", "macro_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_MACRO_ACTIONS_TABLE, "template_id", "template_id INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(QUICK_EVENT_MACRO_ACTIONS_TABLE, "display_order", "display_order INTEGER NOT NULL DEFAULT 0")
    }

    private data class HistoryEntry(
        val id: Long,
        val savedAtMs: Long,
        val kind: String,
        val json: String?,
        val payloadId: Long?,
        val deltaJson: String?,
    )

    private fun ensureSnapshotHistorySchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $HISTORY_PAYLOAD_TABLE (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              hash TEXT NOT NULL UNIQUE,
              json TEXT NOT NULL,
              created_at_ms INTEGER NOT NULL,
              size_bytes INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${HISTORY_PAYLOAD_TABLE}_hash ON $HISTORY_PAYLOAD_TABLE(hash);")

        fun hasColumn(table: String, column: String): Boolean {
            return db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    val n = if (nameIdx >= 0) c.getString(nameIdx) else c.getString(1)
                    if (n == column) return@use true
                }
                false
            }
        }

        if (!hasColumn(HISTORY_TABLE, "kind")) {
            db.execSQL("ALTER TABLE $HISTORY_TABLE ADD COLUMN kind TEXT NOT NULL DEFAULT '$HISTORY_KIND_LEGACY'")
        }
        if (!hasColumn(HISTORY_TABLE, "payload_id")) {
            db.execSQL("ALTER TABLE $HISTORY_TABLE ADD COLUMN payload_id INTEGER")
        }
        if (!hasColumn(HISTORY_TABLE, "base_history_id")) {
            db.execSQL("ALTER TABLE $HISTORY_TABLE ADD COLUMN base_history_id INTEGER")
        }
        if (!hasColumn(HISTORY_TABLE, "delta_json")) {
            db.execSQL("ALTER TABLE $HISTORY_TABLE ADD COLUMN delta_json TEXT")
        }
        if (!hasColumn(HISTORY_TABLE, "payload_hash")) {
            db.execSQL("ALTER TABLE $HISTORY_TABLE ADD COLUMN payload_hash TEXT")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${HISTORY_TABLE}_kind_saved ON $HISTORY_TABLE(kind, saved_at_ms DESC, id DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${HISTORY_TABLE}_payload ON $HISTORY_TABLE(payload_id);")
    }


    internal fun openReadableDb(context: Context): SQLiteDatabase = helper(context).readableDatabase

    internal fun openWritableDb(context: Context): SQLiteDatabase = helper(context).writableDatabase

    data class WalCheckpointResult(
        val busy: Int,
        val log: Int,
        val checkpointed: Int,
    )

    @Volatile internal var checkpointFailureForTests: RuntimeException? = null

    /**
     * Best-effort: flush WAL into the main DB file so non-critical callers stay tolerant.
     * SAF SQLite export must use [checkpointWalOrThrow] instead.
     */
    fun checkpointWal(context: Context) {
        runCatching {
            checkpointWalOrThrow(context)
        }
    }

    fun checkpointWalOrThrow(context: Context): WalCheckpointResult {
        checkpointFailureForTests?.let { throw it }
        val db = helper(context).writableDatabase
        return try {
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { c ->
                if (!c.moveToFirst()) {
                    throw IllegalStateException("WAL checkpoint returned no result")
                }
                val result = WalCheckpointResult(
                    busy = c.getInt(0),
                    log = c.getInt(1),
                    checkpointed = c.getInt(2),
                )
                if (result.busy != 0) {
                    throw IllegalStateException(
                        "WAL checkpoint busy=${result.busy} log=${result.log} checkpointed=${result.checkpointed}"
                    )
                }
                result
            }
        } finally {
            db.close()
        }
    }

    fun ensureBaseSchema(context: Context): Boolean {
        val db = helper(context).writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            fun hasTable(name: String): Boolean {
                return db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    arrayOf(name)
                ).use { c -> c.moveToFirst() }
            }

            val hadSnapshot = hasTable(TABLE)
            val hadHistory = hasTable(HISTORY_TABLE)
            val hadAudit = hasTable(AUDIT_TABLE)
            val hadUiPrefsMirror = hasTable("ui_prefs_mirror")
            if (!hadSnapshot || !hadHistory || !hadAudit || !hadUiPrefsMirror) changed = true
            createBaseSchema(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        return changed
    }

    /**
     * Defensive schema hardening: some real-world DBs may have user_version >= DB_VERSION but still
     * miss the session-only tables (e.g. created by older builds or partial migrations).
     *
     * Returns true if we created any missing table/index.
     */
    fun ensureSessionTables(context: Context): Boolean {
        if (sessionSchemaEnsuredInProcess) return false
        val db = helper(context).writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            fun hasTable(name: String): Boolean {
                return db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    arrayOf(name)
                ).use { c -> c.moveToFirst() }
            }

            fun hasIndex(name: String): Boolean {
                return db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='index' AND name=? LIMIT 1",
                    arrayOf(name)
                ).use { c -> c.moveToFirst() }
            }

            val hadSessions = hasTable(SESSIONS_TABLE)
            val hadJoin = hasTable(SESSION_TAGS_TABLE)
            if (!hadSessions || !hadJoin) changed = true

            // Tables (idempotent).
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $SESSIONS_TABLE (
                  id INTEGER PRIMARY KEY,
                  title TEXT NOT NULL,
                  start_ms INTEGER NOT NULL,
                  end_ms INTEGER,
                  expected_end_ms INTEGER,
                  created_at_ms INTEGER NOT NULL,
                  updated_at_ms INTEGER NOT NULL,
                  deleted_at_ms INTEGER
                );
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $SESSION_TAGS_TABLE (
                  session_id INTEGER NOT NULL,
                  tag_id INTEGER NOT NULL,
                  PRIMARY KEY(session_id, tag_id)
                );
                """.trimIndent()
            )

            
            // Columns hardening (v306):
            // CREATE TABLE IF NOT EXISTS does not add missing columns on existing tables.
            // Some users may have a partially-created sessions table from older builds.
            fun hasColumn(table: String, column: String): Boolean {
                return db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    while (c.moveToNext()) {
                        val n = if (nameIdx >= 0) c.getString(nameIdx) else c.getString(1)
                        if (n == column) return@use true
                    }
                    false
                }
            }

            fun addColumnIfMissing(table: String, column: String, ddl: String) {
                if (!hasColumn(table, column)) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN $ddl")
                    changed = true
                }
            }

            // sessions table (defensive defaults for NOT NULL columns)
            addColumnIfMissing(SESSIONS_TABLE, "title", "title TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(SESSIONS_TABLE, "start_ms", "start_ms INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(SESSIONS_TABLE, "end_ms", "end_ms INTEGER")
            addColumnIfMissing(SESSIONS_TABLE, "expected_end_ms", "expected_end_ms INTEGER")
            addColumnIfMissing(SESSIONS_TABLE, "created_at_ms", "created_at_ms INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(SESSIONS_TABLE, "updated_at_ms", "updated_at_ms INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(SESSIONS_TABLE, "deleted_at_ms", "deleted_at_ms INTEGER")

            // session_tags table
            addColumnIfMissing(SESSION_TAGS_TABLE, "session_id", "session_id INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(SESSION_TAGS_TABLE, "tag_id", "tag_id INTEGER NOT NULL DEFAULT 0")

            // Indexes (idempotent).
            val idxTag = "idx_${SESSION_TAGS_TABLE}_tag"
            val idxSession = "idx_${SESSION_TAGS_TABLE}_session"
            if (!hasIndex(idxTag) || !hasIndex(idxSession)) changed = true
            db.execSQL("CREATE INDEX IF NOT EXISTS $idxTag ON $SESSION_TAGS_TABLE(tag_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS $idxSession ON $SESSION_TAGS_TABLE(session_id);")
            val sessionRuntimeIndexes = listOf(
                "idx_${SESSIONS_TABLE}_timeline",
                "idx_${SESSIONS_TABLE}_running",
                "idx_${SESSIONS_TABLE}_updated",
                "idx_${SESSIONS_TABLE}_visible_id",
            )
            if (sessionRuntimeIndexes.any { !hasIndex(it) }) changed = true
            createSessionIndexes(db)
            createAuditIndexes(db)

            // v317 DATA HARDENING:
            // Some older DBs may have a session_tags table without the composite PRIMARY KEY.
            // Ensure uniqueness via a UNIQUE index and auto-clean duplicates/orphans if present.
            // NOTE: This is safe: it only removes redundant edges and edges pointing to missing sessions.
            val uxEdge = "ux_${SESSION_TAGS_TABLE}_edge"
            if (!hasIndex(uxEdge)) {
                // 1) Remove orphan edges (session_id missing)
                runCatching {
                    db.execSQL(
                        "DELETE FROM $SESSION_TAGS_TABLE WHERE session_id NOT IN (SELECT id FROM $SESSIONS_TABLE)"
                    )
                }

                // 2) Remove duplicate edges (keep the smallest rowid per pair)
                // Works for both rowid tables and WITHOUT ROWID tables (noop if rowid unsupported).
                runCatching {
                    db.execSQL(
                        """
                        DELETE FROM $SESSION_TAGS_TABLE
                        WHERE rowid NOT IN (
                          SELECT MIN(rowid) FROM $SESSION_TAGS_TABLE GROUP BY session_id, tag_id
                        );
                        """.trimIndent()
                    )
                }

                // 3) Enforce uniqueness (session_id, tag_id)
                runCatching {
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS $uxEdge ON $SESSION_TAGS_TABLE(session_id, tag_id);"
                    )
                    changed = true
                }.onFailure {
                    CapsuleAudit.warn(
                        code = "SESSION_TAGS_UX_CREATE_FAILED",
                        originCapsule = "PERSISTENCE/SnapshotSqlite",
                        targetCapsule = "PERSISTENCE",
                        detail = "Failed to create UNIQUE index on session_tags(session_id, tag_id): ${it::class.java.simpleName}: ${it.message}".take(300)
                    )
                }
            }

            // v317 DATA HARDENING: fix impossible intervals (end < start) to prevent negative durations.
            runCatching {
                db.execSQL(
                    """
                    UPDATE $SESSIONS_TABLE
                    SET end_ms = start_ms,
                        updated_at_ms = CASE WHEN updated_at_ms < created_at_ms THEN created_at_ms ELSE updated_at_ms END
                    WHERE deleted_at_ms IS NULL
                      AND end_ms IS NOT NULL
                      AND end_ms < start_ms;
                    """.trimIndent()
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        sessionSchemaEnsuredInProcess = true
        return changed
    }

    fun ensureQuickEventTables(context: Context): Boolean {
        if (quickEventSchemaEnsuredInProcess) return false
        val db = helper(context).writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            fun hasTable(name: String): Boolean {
                return db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    arrayOf(name)
                ).use { c -> c.moveToFirst() }
            }

            fun hasIndex(name: String): Boolean {
                return db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='index' AND name=? LIMIT 1",
                    arrayOf(name)
                ).use { c -> c.moveToFirst() }
            }

            val hadTemplates = hasTable(QUICK_EVENT_TEMPLATES_TABLE)
            val hadTemplateTags = hasTable(QUICK_EVENT_TEMPLATE_TAGS_TABLE)
            val hadEntries = hasTable(QUICK_EVENT_ENTRIES_TABLE)
            val hadEntryTags = hasTable(QUICK_EVENT_ENTRY_TAGS_TABLE)
            val hadFields = hasTable(QUICK_EVENT_TEMPLATE_FIELDS_TABLE)
            val hadFieldValues = hasTable(QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE)
            val hadMacros = hasTable(QUICK_EVENT_MACROS_TABLE)
            val hadMacroTags = hasTable(QUICK_EVENT_MACRO_TAGS_TABLE)
            val hadMacroActions = hasTable(QUICK_EVENT_MACRO_ACTIONS_TABLE)
            if (!hadTemplates || !hadTemplateTags || !hadEntries || !hadEntryTags ||
                !hadFields || !hadFieldValues || !hadMacros || !hadMacroTags || !hadMacroActions
            ) {
                changed = true
            }

            createQuickEventTables(db)
            ensureQuickEventColumns(db) { changed = true }

            val indexes = listOf(
                "idx_${QUICK_EVENT_TEMPLATES_TABLE}_sort",
                "idx_${QUICK_EVENT_TEMPLATES_TABLE}_visible_sort",
                "idx_${QUICK_EVENT_TEMPLATE_TAGS_TABLE}_tag",
                "idx_${QUICK_EVENT_TEMPLATE_TAGS_TABLE}_template",
                "idx_${QUICK_EVENT_ENTRIES_TABLE}_timestamp",
                "idx_${QUICK_EVENT_ENTRIES_TABLE}_visible_timestamp",
                "idx_${QUICK_EVENT_ENTRIES_TABLE}_template",
                "idx_${QUICK_EVENT_ENTRIES_TABLE}_macro",
                "idx_${QUICK_EVENT_ENTRY_TAGS_TABLE}_tag",
                "idx_${QUICK_EVENT_ENTRY_TAGS_TABLE}_entry",
                "idx_${QUICK_EVENT_TEMPLATE_FIELDS_TABLE}_template",
                "idx_${QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE}_entry",
                "idx_${QUICK_EVENT_MACROS_TABLE}_sort",
                "idx_${QUICK_EVENT_MACROS_TABLE}_visible_sort",
                "idx_${QUICK_EVENT_MACRO_TAGS_TABLE}_tag",
                "idx_${QUICK_EVENT_MACRO_TAGS_TABLE}_macro",
                "idx_${QUICK_EVENT_MACRO_ACTIONS_TABLE}_macro",
                "idx_${QUICK_EVENT_MACRO_ACTIONS_TABLE}_template",
                "ux_${QUICK_EVENT_TEMPLATE_TAGS_TABLE}_edge",
                "ux_${QUICK_EVENT_ENTRY_TAGS_TABLE}_edge",
                "ux_${QUICK_EVENT_MACRO_TAGS_TABLE}_edge",
            )
            if (indexes.any { !hasIndex(it) }) {
                changed = true
            }
            runCatching {
                db.execSQL("DELETE FROM $QUICK_EVENT_TEMPLATE_TAGS_TABLE WHERE template_id NOT IN (SELECT id FROM $QUICK_EVENT_TEMPLATES_TABLE)")
                db.execSQL("DELETE FROM $QUICK_EVENT_ENTRY_TAGS_TABLE WHERE entry_id NOT IN (SELECT id FROM $QUICK_EVENT_ENTRIES_TABLE)")
                db.execSQL("DELETE FROM $QUICK_EVENT_TEMPLATE_FIELDS_TABLE WHERE template_id NOT IN (SELECT id FROM $QUICK_EVENT_TEMPLATES_TABLE)")
                db.execSQL("DELETE FROM $QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE WHERE entry_id NOT IN (SELECT id FROM $QUICK_EVENT_ENTRIES_TABLE)")
                db.execSQL("DELETE FROM $QUICK_EVENT_MACRO_TAGS_TABLE WHERE macro_id NOT IN (SELECT id FROM $QUICK_EVENT_MACROS_TABLE)")
                db.execSQL("DELETE FROM $QUICK_EVENT_MACRO_ACTIONS_TABLE WHERE macro_id NOT IN (SELECT id FROM $QUICK_EVENT_MACROS_TABLE)")
                db.execSQL("DELETE FROM $QUICK_EVENT_MACRO_ACTIONS_TABLE WHERE template_id NOT IN (SELECT id FROM $QUICK_EVENT_TEMPLATES_TABLE)")
            }
            runCatching {
                db.execSQL(
                    """
                    DELETE FROM $QUICK_EVENT_TEMPLATE_TAGS_TABLE
                    WHERE rowid NOT IN (
                      SELECT MIN(rowid) FROM $QUICK_EVENT_TEMPLATE_TAGS_TABLE GROUP BY template_id, tag_id
                    );
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    DELETE FROM $QUICK_EVENT_ENTRY_TAGS_TABLE
                    WHERE rowid NOT IN (
                      SELECT MIN(rowid) FROM $QUICK_EVENT_ENTRY_TAGS_TABLE GROUP BY entry_id, tag_id
                    );
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    DELETE FROM $QUICK_EVENT_MACRO_TAGS_TABLE
                    WHERE rowid NOT IN (
                      SELECT MIN(rowid) FROM $QUICK_EVENT_MACRO_TAGS_TABLE GROUP BY macro_id, tag_id
                    );
                    """.trimIndent()
                )
            }
            createQuickEventIndexes(db)
            runCatching {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_${QUICK_EVENT_TEMPLATE_TAGS_TABLE}_edge ON $QUICK_EVENT_TEMPLATE_TAGS_TABLE(template_id, tag_id);")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_${QUICK_EVENT_ENTRY_TAGS_TABLE}_edge ON $QUICK_EVENT_ENTRY_TAGS_TABLE(entry_id, tag_id);")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_${QUICK_EVENT_MACRO_TAGS_TABLE}_edge ON $QUICK_EVENT_MACRO_TAGS_TABLE(macro_id, tag_id);")
            }.onFailure {
                CapsuleAudit.warn(
                    code = "QUICK_EVENT_TAGS_UX_CREATE_FAILED",
                    originCapsule = "PERSISTENCE/SnapshotSqlite",
                    targetCapsule = "PERSISTENCE",
                    detail = "Failed to create UNIQUE index on quick event tag tables: ${it::class.java.simpleName}: ${it.message}".take(300)
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        quickEventSchemaEnsuredInProcess = true
        return changed
    }

    @CapsuleWriteApi
    internal fun writeSnapshot(context: Context, json: String) {
        CapsuleAudit.auditPersistenceWrite("SnapshotSqlite.writeSnapshot")
        val now = System.currentTimeMillis()
        ensureBaseSchema(context)
        val db = helper(context).writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("id", 1)
                put("json", json)
                put("saved_at_ms", now)
            }
            writeTimeMachineHistoryEvent(db, json, now)
            val rowId = db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId == -1L) {
                throw IllegalStateException("Snapshot write returned -1")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "SnapshotSqlite.writeSnapshot")
    }

    private fun writeTimeMachineHistoryEvent(db: SQLiteDatabase, json: String, now: Long) {
        ensureSnapshotHistorySchema(db)
        val latest = readLatestHistoryEntry(db)
        val latestJson = latest?.let { readHistoryEntryJson(db, it) }
        val deltaJson = latestJson?.let { SnapshotHistoryCodec.diffOrNull(it, json) }
        if (latest != null && deltaJson == null) return

        val lastCheckpointMs = readLastCheckpointMs(db)
        val checkpoint = SnapshotHistoryCodec.shouldStoreCheckpoint(lastCheckpointMs, now, deltaJson)
        if (checkpoint || latestJson == null) {
            val hash = SnapshotHistoryCodec.sha256(json)
            val payloadId = upsertSnapshotPayload(db, hash, json, now)
            val historyCv = ContentValues().apply {
                put("json", "")
                put("saved_at_ms", now)
                put("kind", HISTORY_KIND_CHECKPOINT)
                put("payload_id", payloadId)
                put("payload_hash", hash)
            }
            db.insertOrThrow(HISTORY_TABLE, null, historyCv)
        } else {
            val historyCv = ContentValues().apply {
                put("json", "")
                put("saved_at_ms", now)
                put("kind", HISTORY_KIND_DELTA)
                put("base_history_id", latest.id)
                put("delta_json", deltaJson)
            }
            db.insertOrThrow(HISTORY_TABLE, null, historyCv)
        }
    }

    private fun upsertSnapshotPayload(
        db: SQLiteDatabase,
        hash: String,
        json: String,
        now: Long,
    ): Long {
        db.rawQuery(
            "SELECT id FROM $HISTORY_PAYLOAD_TABLE WHERE hash = ? LIMIT 1",
            arrayOf(hash)
        ).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val cv = ContentValues().apply {
            put("hash", hash)
            put("json", json)
            put("created_at_ms", now)
            put("size_bytes", json.toByteArray(Charsets.UTF_8).size)
        }
        val inserted = db.insertWithOnConflict(HISTORY_PAYLOAD_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted != -1L) return inserted
        db.rawQuery(
            "SELECT id FROM $HISTORY_PAYLOAD_TABLE WHERE hash = ? LIMIT 1",
            arrayOf(hash)
        ).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        throw IllegalStateException("Snapshot payload insert failed")
    }

    fun readSnapshot(context: Context): String? {
        val db = helper(context).readableDatabase
        return try {
            db.rawQuery(
                "SELECT json FROM $TABLE WHERE id = 1 ORDER BY saved_at_ms DESC LIMIT 1",
                emptyArray()
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } finally {
            db.close()
        }
    }

    fun hasSnapshot(context: Context): Boolean {
        val db = helper(context).readableDatabase
        return try {
            db.rawQuery(
                "SELECT 1 FROM $TABLE WHERE id = 1 LIMIT 1",
                emptyArray()
            ).use { c -> c.moveToFirst() }
        } finally {
            db.close()
        }
    }

    fun readSnapshotAsOf(context: Context, targetMs: Long): String? {
        val db = helper(context).readableDatabase
        return try {
            readHistoryJsonAsOf(db, targetMs) ?: db.rawQuery(
                "SELECT json FROM $TABLE WHERE saved_at_ms <= ? ORDER BY saved_at_ms DESC LIMIT 1",
                arrayOf(targetMs.toString())
            ).use { current ->
                if (current.moveToFirst()) current.getString(0) else null
            }
        } finally {
            db.close()
        }
    }

    private fun readHistoryJsonAsOf(db: SQLiteDatabase, targetMs: Long): String? {
        ensureSnapshotHistorySchema(db)
        val target = db.rawQuery(
            "SELECT id, saved_at_ms, kind, json, payload_id, delta_json FROM $HISTORY_TABLE WHERE saved_at_ms <= ? ORDER BY saved_at_ms DESC, id DESC LIMIT 1",
            arrayOf(targetMs.toString())
        ).use { c ->
            if (c.moveToFirst()) c.toHistoryEntry() else null
        } ?: return null

        if (target.kind == HISTORY_KIND_LEGACY || !target.json.isNullOrEmpty()) {
            return target.json
        }
        if (target.kind == HISTORY_KIND_CHECKPOINT) {
            return readHistoryEntryJson(db, target)
        }

        val base = db.rawQuery(
            """
            SELECT id, saved_at_ms, kind, json, payload_id, delta_json
            FROM $HISTORY_TABLE
            WHERE id <= ?
              AND (kind = ? OR kind = ? OR (json IS NOT NULL AND length(json) > 0))
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(target.id.toString(), HISTORY_KIND_CHECKPOINT, HISTORY_KIND_LEGACY)
        ).use { c ->
            if (c.moveToFirst()) c.toHistoryEntry() else null
        } ?: return null

        var json = readHistoryEntryJson(db, base) ?: return null
        db.rawQuery(
            """
            SELECT id, saved_at_ms, kind, json, payload_id, delta_json
            FROM $HISTORY_TABLE
            WHERE id > ? AND id <= ?
            ORDER BY id ASC
            """.trimIndent(),
            arrayOf(base.id.toString(), target.id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val entry = c.toHistoryEntry()
                json = when (entry.kind) {
                    HISTORY_KIND_DELTA -> entry.deltaJson?.let { SnapshotHistoryCodec.applyDelta(json, it) } ?: json
                    else -> readHistoryEntryJson(db, entry) ?: json
                }
            }
        }
        return json
    }

    private fun readLatestHistoryEntry(db: SQLiteDatabase): HistoryEntry? {
        return db.rawQuery(
            "SELECT id, saved_at_ms, kind, json, payload_id, delta_json FROM $HISTORY_TABLE ORDER BY id DESC LIMIT 1",
            emptyArray()
        ).use { c ->
            if (c.moveToFirst()) c.toHistoryEntry() else null
        }
    }

    private fun readLastCheckpointMs(db: SQLiteDatabase): Long? {
        return db.rawQuery(
            "SELECT saved_at_ms FROM $HISTORY_TABLE WHERE kind = ? ORDER BY saved_at_ms DESC, id DESC LIMIT 1",
            arrayOf(HISTORY_KIND_CHECKPOINT)
        ).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    private fun readHistoryEntryJson(db: SQLiteDatabase, entry: HistoryEntry): String? {
        if (!entry.json.isNullOrEmpty()) return entry.json
        val payloadId = entry.payloadId ?: return null
        return db.rawQuery(
            "SELECT json FROM $HISTORY_PAYLOAD_TABLE WHERE id = ? LIMIT 1",
            arrayOf(payloadId.toString())
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun android.database.Cursor.toHistoryEntry(): HistoryEntry {
        val json = if (isNull(3)) null else getString(3)
        val payloadId = if (isNull(4)) null else getLong(4)
        val delta = if (isNull(5)) null else getString(5)
        return HistoryEntry(
            id = getLong(0),
            savedAtMs = getLong(1),
            kind = getString(2) ?: HISTORY_KIND_LEGACY,
            json = json,
            payloadId = payloadId,
            deltaJson = delta,
        )
    }

    fun compactTimeMachineStorage(context: Context): Boolean {
        val db = helper(context).writableDatabase
        var changed = false
        try {
            ensureSnapshotHistorySchema(db)
            val legacyRows = ArrayList<HistoryEntry>(LEGACY_COMPACTION_BATCH_ROWS)
            db.rawQuery(
                """
                SELECT id, saved_at_ms, kind, json, payload_id, delta_json
                FROM $HISTORY_TABLE
                WHERE json IS NOT NULL AND length(json) > 0
                ORDER BY id ASC
                LIMIT $LEGACY_COMPACTION_BATCH_ROWS
                """.trimIndent(),
                emptyArray()
            ).use { c ->
                while (c.moveToNext()) legacyRows.add(c.toHistoryEntry())
            }
            if (legacyRows.isEmpty()) {
                Log.i(TAG, "time machine compaction: no legacy snapshot_history rows remain")
                return false
            }

            var previousJson: String? = legacyRows.firstOrNull()?.let { first ->
                db.rawQuery(
                    "SELECT id, saved_at_ms, kind, json, payload_id, delta_json FROM $HISTORY_TABLE WHERE id < ? ORDER BY id DESC LIMIT 1",
                    arrayOf(first.id.toString())
                ).use { c ->
                    if (c.moveToFirst()) readHistoryEntryJson(db, c.toHistoryEntry()) else null
                }
            }
            var lastCheckpointMs = readLastCheckpointMs(db)

            db.beginTransaction()
            try {
                legacyRows.forEach { row ->
                    val currentJson = row.json ?: return@forEach
                    val delta = previousJson?.let { SnapshotHistoryCodec.diffOrNull(it, currentJson) }
                    val checkpoint = SnapshotHistoryCodec.shouldStoreCheckpoint(lastCheckpointMs, row.savedAtMs, delta)
                    val cv = ContentValues()
                    if (checkpoint || previousJson == null || delta == null) {
                        val hash = SnapshotHistoryCodec.sha256(currentJson)
                        val payloadId = upsertSnapshotPayload(db, hash, currentJson, row.savedAtMs)
                        cv.put("kind", HISTORY_KIND_CHECKPOINT)
                        cv.put("payload_id", payloadId)
                        cv.put("payload_hash", hash)
                        cv.putNull("delta_json")
                        cv.putNull("base_history_id")
                        lastCheckpointMs = row.savedAtMs
                    } else {
                        cv.put("kind", HISTORY_KIND_DELTA)
                        cv.put("delta_json", delta)
                        cv.putNull("payload_id")
                        cv.putNull("payload_hash")
                        cv.put("base_history_id", row.id - 1L)
                    }
                    cv.put("json", "")
                    db.update(HISTORY_TABLE, cv, "id = ?", arrayOf(row.id.toString()))
                    previousJson = currentJson
                    changed = true
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val remaining = db.rawQuery(
                "SELECT COUNT(*) FROM $HISTORY_TABLE WHERE json IS NOT NULL AND length(json) > 0",
                emptyArray()
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
            Log.i(TAG, "time machine compaction: compacted=${legacyRows.size} remainingLegacy=$remaining")
        } finally {
            db.close()
        }

        if (changed) checkpointWal(context)
        return changed
    }

    fun applyStorageRetention(context: Context): Boolean {
        val db = helper(context).writableDatabase
        var changed = false
        try {
            ensureSnapshotHistorySchema(db)
            createAuditIndexes(db)
            db.beginTransaction()
            try {
                changed = trimSnapshotHistoryLocked(db) || changed
                changed = trimAuditEventsLocked(db) || changed
                changed = deleteOrphanSnapshotPayloadsLocked(db) || changed
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
        if (changed) checkpointWal(context)
        return changed
    }

    private fun trimSnapshotHistoryLocked(db: SQLiteDatabase): Boolean {
        val total = db.rawQuery("SELECT COUNT(*) FROM $HISTORY_TABLE", emptyArray()).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        if (total <= MAX_SNAPSHOT_HISTORY_ROWS) return false

        val targetOffset = (MAX_SNAPSHOT_HISTORY_ROWS - 1).coerceAtLeast(0)
        val targetId = db.rawQuery(
            "SELECT id FROM $HISTORY_TABLE ORDER BY id DESC LIMIT 1 OFFSET $targetOffset",
            emptyArray()
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false

        val boundaryId = db.rawQuery(
            """
            SELECT id FROM $HISTORY_TABLE
            WHERE id <= ?
              AND (
                kind = ? OR kind = ? OR
                (json IS NOT NULL AND length(json) > 0) OR
                payload_id IS NOT NULL
              )
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(targetId.toString(), HISTORY_KIND_CHECKPOINT, HISTORY_KIND_LEGACY)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false

        val deleted = db.delete(HISTORY_TABLE, "id < ?", arrayOf(boundaryId.toString()))
        return deleted > 0
    }

    private fun trimAuditEventsLocked(db: SQLiteDatabase): Boolean {
        val total = db.rawQuery("SELECT COUNT(*) FROM $AUDIT_TABLE", emptyArray()).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        if (total <= MAX_AUDIT_EVENT_ROWS) return false

        val cutoffId = db.rawQuery(
            "SELECT id FROM $AUDIT_TABLE ORDER BY id DESC LIMIT 1 OFFSET ${MAX_AUDIT_EVENT_ROWS - 1}",
            emptyArray()
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false
        return db.delete(AUDIT_TABLE, "id < ?", arrayOf(cutoffId.toString())) > 0
    }

    private fun deleteOrphanSnapshotPayloadsLocked(db: SQLiteDatabase): Boolean {
        val before = db.rawQuery("SELECT COUNT(*) FROM $HISTORY_PAYLOAD_TABLE", emptyArray()).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        db.execSQL(
            """
            DELETE FROM $HISTORY_PAYLOAD_TABLE
            WHERE id NOT IN (
              SELECT DISTINCT payload_id FROM $HISTORY_TABLE WHERE payload_id IS NOT NULL
            )
            """.trimIndent()
        )
        val after = db.rawQuery("SELECT COUNT(*) FROM $HISTORY_PAYLOAD_TABLE", emptyArray()).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        return after < before
    }

    fun vacuumTimeMachineStorageIfCompacted(context: Context): Boolean {
        val db = helper(context).writableDatabase
        return try {
            ensureSnapshotHistorySchema(db)
            val legacyRowsRemain = db.rawQuery(
                "SELECT 1 FROM $HISTORY_TABLE WHERE json IS NOT NULL AND length(json) > 0 LIMIT 1",
                emptyArray()
            ).use { c -> c.moveToFirst() }
            if (legacyRowsRemain) {
                Log.i(TAG, "time machine vacuum skipped: legacy snapshot_history rows remain")
                return false
            }
            val pageSize = db.rawQuery("PRAGMA page_size", emptyArray()).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 4096L
            }
            val freelistCount = db.rawQuery("PRAGMA freelist_count", emptyArray()).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            val reclaimBytes = pageSize * freelistCount
            if (reclaimBytes < MIN_VACUUM_RECLAIM_BYTES) {
                Log.i(TAG, "time machine vacuum skipped: reclaimBytes=$reclaimBytes")
                return false
            }
            Log.i(TAG, "time machine vacuum start")
            db.execSQL("VACUUM")
            Log.i(TAG, "time machine vacuum complete")
            true
        } finally {
            db.close()
        }
    }

    fun internalDbFile(context: Context): java.io.File {
        return context.applicationContext.getDatabasePath(DB_NAME)
    }
}
