// v376
package com.example.multitimetracker.persistence

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import com.example.multitimetracker.BuildConfig
import com.example.multitimetracker.model.LifePeriodDurationMode
import org.json.JSONObject

/**
 * Tiny UI preferences stored in SharedPreferences.
 * Keep this intentionally small: only view toggles that affect rendering.
 */
// === FEATURE CAPSULE: UiPrefsStore (Repository) START ===
@OptIn(com.example.multitimetracker.util.CapsuleWriteApi::class)
object UiPrefsStore {
    private const val PREFS = "ui_prefs"

    private const val MIRROR_TABLE = "ui_prefs_mirror"

    // v391: one-time auto-consistency run marker (mirrored into DB)
    const val KEY_LAST_AUTOCONSIST_PATCH = "autoConsistency_lastPatch"


fun getLong(context: Context, key: String, default: Long): Long {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return getLongCompat(prefs, key) ?: default
}

fun putLong(context: Context, key: String, value: Long) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit().putLong(key, value).apply()
}

/**
 * Mirror all UI prefs into the internal SQLite DB so that the DB vault copy contains
 * everything needed to restore app state after reinstall.
 *
 * NOTE: This mirrors the raw SharedPreferences map (prefs.all).
 */
fun mirrorAllToSqlite(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val map = prefs.all
    val json = JSONObject()

    for ((k, v) in map) {
        when (v) {
            null -> json.put(k, JSONObject.NULL)
            is Boolean, is Int, is Long, is Float, is Double, is String -> json.put(k, v)
            is Set<*> -> {
                val arr = org.json.JSONArray()
                v.filterIsInstance<String>().forEach { arr.put(it) }
                json.put(k, arr)
            }
            else -> json.put(k, v.toString())
        }
    }

    val now = System.currentTimeMillis()
    val db = SnapshotSqlite.openWritableDb(context)
    db.beginTransaction()
    try {
        val cv = android.content.ContentValues().apply {
            put("id", 1)
            put("json", json.toString())
            put("saved_at_ms", now)
        }
        db.insertWithOnConflict(MIRROR_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }
}

/**
 * Restore UI prefs from the internal SQLite DB (if present).
 * If overwrite=true, clears current SharedPreferences and repopulates from DB.
 */
fun restoreAllFromSqliteIfPresent(context: Context, overwrite: Boolean) {
    val db = SnapshotSqlite.openReadableDb(context)
    val jsonStr = runCatching {
        db.rawQuery("SELECT json FROM $MIRROR_TABLE WHERE id=1 LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    db.close()

    if (jsonStr.isNullOrBlank()) return
    val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return

    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val ed = prefs.edit()
    if (overwrite) ed.clear()

    val it = obj.keys()
    while (it.hasNext()) {
        val k = it.next()
        val v = obj.opt(k)
        when (v) {
            null, JSONObject.NULL -> ed.remove(k)
            is Boolean -> ed.putBoolean(k, v)
            is Int -> ed.putInt(k, v)
            is Long -> ed.putLong(k, v)
            is Double -> ed.putFloat(k, v.toFloat())
            is String -> ed.putString(k, v)
            is org.json.JSONArray -> {
                val set = LinkedHashSet<String>()
                for (i in 0 until v.length()) {
                    val sv = v.optString(i, null)
                    if (!sv.isNullOrBlank()) set.add(sv)
                }
                ed.putStringSet(k, set)
            }
            else -> ed.putString(k, v.toString())
        }
    }
    ed.apply()
}


    private const val KEY_SESSION_ONLY_MODE = "session_only_mode"
    private const val KEY_SESSIONS_BOOTSTRAP_DONE = "sessions_bootstrap_done"

    private const val KEY_HIDE_INACTIVE_TIME = "hide_inactive_task_time"
    private const val KEY_HIDE_INACTIVE_TAGS = "hide_inactive_task_tags"
    private const val KEY_TIME_FENCE_CRITICAL_DISCLAIMER_SHOWN = "time_fence_critical_disclaimer_shown"

    // Developer-only toggles. These must stay unreachable in release builds.
    private const val KEY_DEV_CHRONOLOGY_USE_SESSIONS = "dev_chronology_use_sessions"

    // Hidden developer mode for debug/dev builds only.
    private const val KEY_DEV_MODE_ENABLED = "dev_mode_enabled"
    private const val KEY_SHOW_SECONDS = "show_seconds"
    private const val KEY_HIDE_HOURS_IF_ZERO = "hide_hours_if_zero"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_LIFE_PERIOD_DURATION_MODE = "life_period_duration_mode"

    // Timeline v2: show/hide tags column in the list.
    private const val KEY_TIMELINE_SHOW_TAGS_IN_LIST = "timeline_show_tags_in_list"

    private const val KEY_REQUIRE_LONG_PRESS_TOGGLE = "require_long_press_toggle"
    private const val KEY_IGNORE_SHORT_SESSIONS = "ignore_short_sessions"
    private const val KEY_IGNORE_SHORT_THRESHOLD_SECS = "ignore_short_threshold_secs"

    // Legacy audit toggles (kept for migration)
    private const val KEY_AUDIT_SHOW_SYSTEM = "audit_show_system"
    private const val KEY_AUDIT_SHOW_UNDONE = "audit_show_undone"

    // Audit log filters
    private const val KEY_AUDIT_FILTER_TASKS = "audit_filter_tasks"
    private const val KEY_AUDIT_FILTER_TAGS = "audit_filter_tags"
    private const val KEY_AUDIT_FILTER_ALERTS = "audit_filter_alerts"
    private const val KEY_AUDIT_FILTER_CHAINS = "audit_filter_chains"
    private const val KEY_AUDIT_FILTER_SYSTEM = "audit_filter_system"
    private const val KEY_AUDIT_FILTER_UNDONE = "audit_filter_undone"

    private const val KEY_AUDIT_UNDO_ENABLED = "audit_undo_enabled"
    private const val KEY_LAST_LOGGED_APP_VERSION_CODE = "last_logged_app_version_code"
    private const val KEY_FIRST_INSTALLED_APP_VERSION_CODE = "first_installed_app_version_code"
    private const val KEY_FIRST_INSTALL_TIME_MS = "first_install_time_ms"

    /**
     * Returns the OS-reported first install time for this package.
     * This is stable across app runs, and changes when the app is truly reinstalled.
     */
    fun currentFirstInstallTimeMs(context: Context): Long {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            info.firstInstallTime
        } catch (_: Throwable) {
            0L
        }
    }

    fun getStoredFirstInstallTimeMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (!prefs.contains(KEY_FIRST_INSTALL_TIME_MS)) null else getLongCompat(prefs, KEY_FIRST_INSTALL_TIME_MS)
    }

    fun setStoredFirstInstallTimeMs(context: Context, valueMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_FIRST_INSTALL_TIME_MS, valueMs)
            .apply()
    }

    // v114: one-time bootstrap guard for session-only tables.
    // Prevents accidental resurrection when the user intentionally deletes all sessions.
    private const val KEY_SESSION_BOOTSTRAP_DONE = "session_bootstrap_done"

    private const val KEY_LAST_IMPORT_MS = "last_import_ms"
    private const val KEY_LAST_AUTO_EXPORT_MS = "last_auto_export_ms"
    private const val KEY_LAST_MANUAL_EXPORT_MS = "last_manual_export_ms"

    // v271: backup UX — persist last export/import signatures & hashes so UI can display them.
    private const val KEY_LAST_MANUAL_EXPORT_ZIP_NAME = "last_manual_export_zip_name"
    private const val KEY_LAST_MANUAL_EXPORT_ZIP_SHA256 = "last_manual_export_zip_sha256"
    private const val KEY_LAST_MANUAL_EXPORT_BACKUP_SIGNATURE = "last_manual_export_backup_signature"

    private const val KEY_LAST_IMPORT_DB_FILE_NAME = "last_import_db_file_name"
    private const val KEY_LAST_IMPORT_DB_SHA256 = "last_import_db_sha256"
    private const val KEY_LAST_IMPORT_BEFORE_SIGNATURE = "last_import_before_signature"
    private const val KEY_LAST_IMPORT_AFTER_SIGNATURE = "last_import_after_signature"
    // v211: cold-start guardrail — never auto-restore DB from user folder unless explicitly enabled.
    private const val KEY_VAULT_AUTO_RESTORE_ENABLED = "vault_auto_restore_enabled"

    fun getHideInactiveTime(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_INACTIVE_TIME, false)

    fun setHideInactiveTime(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_INACTIVE_TIME, value)
            .apply()
        trackPersistentSetting(context, "setHideInactiveTime")
    }

    fun getHideInactiveTags(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_INACTIVE_TAGS, false)

    fun setHideInactiveTags(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_INACTIVE_TAGS, value)
            .apply()
        trackPersistentSetting(context, "setHideInactiveTags")
    }

    fun getShowSeconds(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SECONDS, true)

    fun setShowSeconds(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_SECONDS, value)
            .apply()
        trackPersistentSetting(context, "setShowSeconds")
    }

    fun getHideHoursIfZero(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_HOURS_IF_ZERO, false)

    fun setHideHoursIfZero(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_HOURS_IF_ZERO, value)
            .apply()
        trackPersistentSetting(context, "setHideHoursIfZero")
    }

    fun getTimelineShowTagsInList(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TIMELINE_SHOW_TAGS_IN_LIST, true)

    fun setTimelineShowTagsInList(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TIMELINE_SHOW_TAGS_IN_LIST, value)
            .apply()
        trackPersistentSetting(context, "setTimelineShowTagsInList")
    }

    fun getKeepScreenOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_SCREEN_ON, value)
            .apply()
        trackPersistentSetting(context, "setKeepScreenOn")
    }

    fun getLifePeriodDurationMode(context: Context): LifePeriodDurationMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIFE_PERIOD_DURATION_MODE, null)
        return LifePeriodDurationMode.entries.firstOrNull { it.name == raw }
            ?: LifePeriodDurationMode.EXACT_DURATION
    }

    fun setLifePeriodDurationMode(context: Context, value: LifePeriodDurationMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIFE_PERIOD_DURATION_MODE, value.name)
            .apply()
        trackPersistentSetting(context, "setLifePeriodDurationMode")
    }

    data class LastManualExportMeta(
        val zipName: String?,
        val zipSha256: String?,
        val backupSignature: String?
    )

    fun getLastManualExportMeta(context: Context): LastManualExportMeta {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LastManualExportMeta(
            zipName = prefs.getString(KEY_LAST_MANUAL_EXPORT_ZIP_NAME, null),
            zipSha256 = prefs.getString(KEY_LAST_MANUAL_EXPORT_ZIP_SHA256, null),
            backupSignature = prefs.getString(KEY_LAST_MANUAL_EXPORT_BACKUP_SIGNATURE, null)
        )
    }

    fun setLastManualExportMeta(context: Context, zipName: String?, zipSha256: String?, backupSignature: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MANUAL_EXPORT_ZIP_NAME, zipName)
            .putString(KEY_LAST_MANUAL_EXPORT_ZIP_SHA256, zipSha256)
            .putString(KEY_LAST_MANUAL_EXPORT_BACKUP_SIGNATURE, backupSignature)
            .apply()
    }

    data class LastImportMeta(
        val dbFileName: String?,
        val dbSha256: String?,
        val beforeSignature: String?,
        val afterSignature: String?
    )

    fun getLastImportMeta(context: Context): LastImportMeta {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LastImportMeta(
            dbFileName = prefs.getString(KEY_LAST_IMPORT_DB_FILE_NAME, null),
            dbSha256 = prefs.getString(KEY_LAST_IMPORT_DB_SHA256, null),
            beforeSignature = prefs.getString(KEY_LAST_IMPORT_BEFORE_SIGNATURE, null),
            afterSignature = prefs.getString(KEY_LAST_IMPORT_AFTER_SIGNATURE, null)
        )
    }

    fun setLastImportMeta(
        context: Context,
        dbFileName: String?,
        dbSha256: String?,
        beforeSignature: String?,
        afterSignature: String?
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_IMPORT_DB_FILE_NAME, dbFileName)
            .putString(KEY_LAST_IMPORT_DB_SHA256, dbSha256)
            .putString(KEY_LAST_IMPORT_BEFORE_SIGNATURE, beforeSignature)
            .putString(KEY_LAST_IMPORT_AFTER_SIGNATURE, afterSignature)
            .apply()
    }

    fun getTimeFenceCriticalDisclaimerShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TIME_FENCE_CRITICAL_DISCLAIMER_SHOWN, false)

    fun setTimeFenceCriticalDisclaimerShown(context: Context, shown: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TIME_FENCE_CRITICAL_DISCLAIMER_SHOWN, shown)
            .apply()
        trackPersistentSetting(context, "setTimeFenceCriticalDisclaimerShown")
    }



    fun getRequireLongPressToggle(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_LONG_PRESS_TOGGLE, true)

    fun setRequireLongPressToggle(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUIRE_LONG_PRESS_TOGGLE, value)
            .apply()
        trackPersistentSetting(context, "setRequireLongPressToggle")
    }

    fun getIgnoreShortSessions(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_IGNORE_SHORT_SESSIONS, true)

    fun setIgnoreShortSessions(context: Context, value: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getBoolean(KEY_IGNORE_SHORT_SESSIONS, true)
        if (old == value) return

        prefs.edit().putBoolean(KEY_IGNORE_SHORT_SESSIONS, value).apply()
        trackPersistentSetting(context, "setIgnoreShortSessions")

        val thresholdSecs = getIgnoreShortSessionsThresholdSecs(context)
        val payload = JSONObject()
            .put("enabled", value)
            .put("thresholdSecs", thresholdSecs)

        val mm = (thresholdSecs / 60).coerceAtLeast(0)
        val ss = (thresholdSecs % 60).coerceAtLeast(0)
        val mmss = "%02d:%02d".format(mm, ss)
        AuditLogSqlite.insert(
            context = context,
            action = "PREF_IGNORE_SHORT_SESSIONS",
            summary = "ignore_short_sessions=${if (value) "ON" else "OFF"} (threshold=$mmss)",
            payload = payload,
            isSystem = true,
        )
    }

    fun getIgnoreShortSessionsThresholdSecs(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_IGNORE_SHORT_THRESHOLD_SECS, 5)

    fun setIgnoreShortSessionsThresholdSecs(context: Context, valueSecs: Int) {
        val v = valueSecs.coerceAtLeast(0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getInt(KEY_IGNORE_SHORT_THRESHOLD_SECS, 5)
        if (old == v) return

        prefs.edit().putInt(KEY_IGNORE_SHORT_THRESHOLD_SECS, v).apply()
        trackPersistentSetting(context, "setIgnoreShortSessionsThresholdSecs")

        val enabled = getIgnoreShortSessions(context)
        val payload = JSONObject()
            .put("enabled", enabled)
            .put("thresholdSecs", v)

        val mm = (v / 60).coerceAtLeast(0)
        val ss = (v % 60).coerceAtLeast(0)
        val mmss = "%02d:%02d".format(mm, ss)
        AuditLogSqlite.insert(
            context = context,
            action = "PREF_IGNORE_SHORT_THRESHOLD",
            summary = "ignore_short_threshold=$mmss (enabled=${if (enabled) "ON" else "OFF"})",
            payload = payload,
            isSystem = true,
        )
    }

    // --- Audit log filters ---

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun trackPersistentSetting(context: Context, source: String) {
        PersistentMutationTracker.record(context, "UiPrefsStore.$source")
    }
/**
 * SharedPreferences compat read: older versions may have stored version codes as Int.
 * We migrate to Long on read to avoid ClassCastException after upgrades/imports.
 */
private fun getLongCompat(p: SharedPreferences, key: String): Long? {
    val raw = p.all[key] ?: return null
    val v: Long? = when (raw) {
        is Long -> raw
        is Int -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }
    if (v != null && raw !is Long) {
        p.edit().putLong(key, v).apply()
    }
    return v
}

    fun getAuditFilterTasks(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIT_FILTER_TASKS, true)

    fun setAuditFilterTasks(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIT_FILTER_TASKS, value).apply()
        trackPersistentSetting(context, "setAuditFilterTasks")
    }

    fun getAuditFilterTags(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIT_FILTER_TAGS, true)

    fun setAuditFilterTags(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIT_FILTER_TAGS, value).apply()
        trackPersistentSetting(context, "setAuditFilterTags")
    }

    fun getAuditFilterAlerts(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIT_FILTER_ALERTS, true)

    fun setAuditFilterAlerts(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIT_FILTER_ALERTS, value).apply()
        trackPersistentSetting(context, "setAuditFilterAlerts")
    }

    fun getAuditFilterChains(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIT_FILTER_CHAINS, true)

    fun setAuditFilterChains(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIT_FILTER_CHAINS, value).apply()
        trackPersistentSetting(context, "setAuditFilterChains")
    }

    fun getAuditFilterSystem(context: Context): Boolean {
        val p = prefs(context)
        // Migration: if the new key is not present, fall back to legacy KEY_AUDIT_SHOW_SYSTEM (default=true).
        return if (p.contains(KEY_AUDIT_FILTER_SYSTEM)) {
            p.getBoolean(KEY_AUDIT_FILTER_SYSTEM, true)
        } else {
            p.getBoolean(KEY_AUDIT_SHOW_SYSTEM, true)
        }
    }

    fun setAuditFilterSystem(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIT_FILTER_SYSTEM, value).apply()
        trackPersistentSetting(context, "setAuditFilterSystem")
    }

    fun getAuditFilterUndone(context: Context): Boolean {
        val p = prefs(context)
        // Migration: if the new key is not present, fall back to legacy KEY_AUDIT_SHOW_UNDONE.
        return if (p.contains(KEY_AUDIT_FILTER_UNDONE)) {
            p.getBoolean(KEY_AUDIT_FILTER_UNDONE, true)
        } else {
            p.getBoolean(KEY_AUDIT_SHOW_UNDONE, true)
        }
    }

    fun setAuditFilterUndone(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIT_FILTER_UNDONE, value).apply()
        trackPersistentSetting(context, "setAuditFilterUndone")
    }

    // Backwards-compat wrappers (older call sites)
    fun getAuditShowSystem(context: Context): Boolean = getAuditFilterSystem(context)
    fun setAuditShowSystem(context: Context, value: Boolean) = setAuditFilterSystem(context, value)
    fun getAuditShowUndone(context: Context): Boolean = getAuditFilterUndone(context)
    fun setAuditShowUndone(context: Context, value: Boolean) = setAuditFilterUndone(context, value)

    fun getAuditUndoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIT_UNDO_ENABLED, false)

    fun setAuditUndoEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIT_UNDO_ENABLED, value).apply()
        trackPersistentSetting(context, "setAuditUndoEnabled")
    }

    // --- Session-only bootstrap guard ---
    // NOTE: historical key names existed in the wild (singular vs plural). To avoid
    // reinstall/upgrade edge cases, we treat KEY_SESSION_BOOTSTRAP_DONE as canonical,
    // but also read/write the legacy KEY_SESSIONS_BOOTSTRAP_DONE.
    fun getSessionsBootstrapDone(context: Context): Boolean {
        val p = prefs(context)
        return if (p.contains(KEY_SESSION_BOOTSTRAP_DONE)) {
            p.getBoolean(KEY_SESSION_BOOTSTRAP_DONE, false)
        } else {
            // Legacy fallback (older builds)
            p.getBoolean(KEY_SESSIONS_BOOTSTRAP_DONE, false)
        }
    }

    fun setSessionsBootstrapDone(context: Context, value: Boolean) {
        // Write both keys for maximum backwards compatibility.
        prefs(context)
            .edit()
            .putBoolean(KEY_SESSION_BOOTSTRAP_DONE, value)
            .putBoolean(KEY_SESSIONS_BOOTSTRAP_DONE, value)
            .apply()
    }

    fun getLastLoggedAppVersionCode(context: Context): Long? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = getLongCompat(p, KEY_LAST_LOGGED_APP_VERSION_CODE) ?: -1L
        return if (v > 0L) v else null
    }

    fun setLastLoggedAppVersionCode(context: Context, versionCode: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_LOGGED_APP_VERSION_CODE, versionCode)
            .apply()
    }
    // --- Cold-start guardrail ---
    // We use this to distinguish "fresh install / clear data" from "upgrade with existing prefs".
    fun getFirstInstalledAppVersionCode(context: Context): Long? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = getLongCompat(p, KEY_FIRST_INSTALLED_APP_VERSION_CODE) ?: -1L
        return if (v <= 0L) null else v
    }

    fun ensureFirstInstalledAppVersionCode(context: Context, currentVersionCode: Long) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if ((getLongCompat(p, KEY_FIRST_INSTALLED_APP_VERSION_CODE) ?: -1L) <= 0L) {
            p.edit().putLong(KEY_FIRST_INSTALLED_APP_VERSION_CODE, currentVersionCode).apply()
        }
    }


    // v114: backwards-compatible wrappers
    fun getSessionBootstrapDone(context: Context): Boolean = getSessionsBootstrapDone(context)
    fun setSessionBootstrapDone(context: Context, value: Boolean) = setSessionsBootstrapDone(context, value)

    // --- Hidden developer mode (debug/dev builds only) ---

    fun isDeveloperSurfaceAvailable(): Boolean = BuildConfig.DEBUG

    fun isDevModeEnabled(context: Context): Boolean =
        if (!isDeveloperSurfaceAvailable()) {
            false
        } else {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEV_MODE_ENABLED, false)
        }

    /** Toggles dev mode and returns the new state. */
    fun toggleDevMode(context: Context): Boolean {
        if (!isDeveloperSurfaceAvailable()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DEV_MODE_ENABLED, false)
                .apply()
            return false
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val newValue = !prefs.getBoolean(KEY_DEV_MODE_ENABLED, false)
        prefs.edit().putBoolean(KEY_DEV_MODE_ENABLED, newValue).apply()
        return newValue
    }

    fun getDevChronologyUseSessions(context: Context): Boolean {
        if (!isDevModeEnabled(context)) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEV_CHRONOLOGY_USE_SESSIONS, false)
    }

    fun setDevChronologyUseSessions(context: Context, value: Boolean) {
        if (!isDevModeEnabled(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEV_CHRONOLOGY_USE_SESSIONS, value)
            .apply()
    }


    fun getLastImportMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (getLongCompat(prefs, KEY_LAST_IMPORT_MS) ?: 0L).takeIf { it > 0L }
    }

    fun setLastImportMs(context: Context, valueMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_IMPORT_MS, valueMs)
            .apply()
    }

    fun getLastAutoExportMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (getLongCompat(prefs, KEY_LAST_AUTO_EXPORT_MS) ?: 0L).takeIf { it > 0L }
    }

    fun setLastAutoExportMs(context: Context, valueMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_AUTO_EXPORT_MS, valueMs)
            .apply()
    }

    fun getLastManualExportMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (getLongCompat(prefs, KEY_LAST_MANUAL_EXPORT_MS) ?: 0L).takeIf { it > 0L }
    }

    fun setLastManualExportMs(context: Context, valueMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_MANUAL_EXPORT_MS, valueMs)
            .apply()
    }

    // v211: default=false. This prevents “data spawn a freddo” after reinstall/clear-data when Android restores prefs.
    fun isVaultAutoRestoreEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VAULT_AUTO_RESTORE_ENABLED, false)

    fun setVaultAutoRestoreEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VAULT_AUTO_RESTORE_ENABLED, enabled)
            .apply()
        trackPersistentSetting(context, "setVaultAutoRestoreEnabled")
    }


    fun isSessionOnlyMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SESSION_ONLY_MODE, true)

    fun setSessionOnlyMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SESSION_ONLY_MODE, enabled)
            .apply()
        trackPersistentSetting(context, "setSessionOnlyMode")
    }
}
// === FEATURE CAPSULE: UiPrefsStore (Repository) END ===
