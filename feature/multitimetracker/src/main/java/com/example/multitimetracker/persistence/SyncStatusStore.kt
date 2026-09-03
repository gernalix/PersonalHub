package com.example.multitimetracker.persistence

import android.content.Context
import java.time.Instant
import java.time.format.DateTimeFormatter

object SyncStatusStore {
    private const val PREFS = "mtt_sync_status"
    private const val KEY_LAST_DATABASE_MUTATION_MS = "last_database_mutation_at_ms"
    private const val KEY_LAST_DATABASE_MUTATION_UTC = "last_database_mutation_at_utc_z"
    private const val KEY_LAST_SUCCESSFUL_EXPORT_MS = "last_successful_export_at_ms"
    private const val KEY_LAST_SUCCESSFUL_EXPORT_UTC = "last_successful_export_at_utc_z"
    private const val KEY_LAST_EXPORT_ATTEMPT_MS = "last_export_attempt_at_ms"
    private const val KEY_LAST_EXPORT_ATTEMPT_UTC = "last_export_attempt_at_utc_z"
    private const val KEY_LAST_EXPORT_STATUS = "last_export_status"
    private const val KEY_LAST_EXPORT_ERROR = "last_export_error"
    private const val KEY_LAST_EXPORT_FILE = "last_export_file"
    private const val KEY_LAST_INTEGRITY_CHECK = "last_integrity_check"
    private const val KEY_LAST_MUTATION_SOURCE = "last_mutation_source"

    enum class ExportStatus { NEVER, IN_PROGRESS, SUCCESS, FAILED }

    data class Snapshot(
        val lastDatabaseMutationAtMs: Long,
        val lastDatabaseMutationAtUtcZ: String?,
        val lastSuccessfulExportAtMs: Long,
        val lastSuccessfulExportAtUtcZ: String?,
        val lastExportAttemptAtMs: Long,
        val lastExportAttemptAtUtcZ: String?,
        val lastExportStatus: ExportStatus,
        val lastExportError: String?,
        val lastExportFile: String?,
        val lastIntegrityCheck: String?,
        val lastMutationSource: String?,
    )

    fun read(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = runCatching {
            ExportStatus.valueOf(prefs.getString(KEY_LAST_EXPORT_STATUS, null) ?: ExportStatus.NEVER.name)
        }.getOrDefault(ExportStatus.NEVER)
        return Snapshot(
            lastDatabaseMutationAtMs = prefs.getLong(KEY_LAST_DATABASE_MUTATION_MS, 0L),
            lastDatabaseMutationAtUtcZ = prefs.getString(KEY_LAST_DATABASE_MUTATION_UTC, null),
            lastSuccessfulExportAtMs = prefs.getLong(KEY_LAST_SUCCESSFUL_EXPORT_MS, 0L),
            lastSuccessfulExportAtUtcZ = prefs.getString(KEY_LAST_SUCCESSFUL_EXPORT_UTC, null),
            lastExportAttemptAtMs = prefs.getLong(KEY_LAST_EXPORT_ATTEMPT_MS, 0L),
            lastExportAttemptAtUtcZ = prefs.getString(KEY_LAST_EXPORT_ATTEMPT_UTC, null),
            lastExportStatus = status,
            lastExportError = prefs.getString(KEY_LAST_EXPORT_ERROR, null),
            lastExportFile = prefs.getString(KEY_LAST_EXPORT_FILE, null),
            lastIntegrityCheck = prefs.getString(KEY_LAST_INTEGRITY_CHECK, null),
            lastMutationSource = prefs.getString(KEY_LAST_MUTATION_SOURCE, null),
        )
    }

    fun markDatabaseMutation(context: Context, source: String, nowMs: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_DATABASE_MUTATION_MS, nowMs)
            .putString(KEY_LAST_DATABASE_MUTATION_UTC, utcZ(nowMs))
            .putString(KEY_LAST_MUTATION_SOURCE, source.take(120))
            .apply()
    }

    fun markExportStarted(context: Context, safFile: String?, nowMs: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_EXPORT_ATTEMPT_MS, nowMs)
            .putString(KEY_LAST_EXPORT_ATTEMPT_UTC, utcZ(nowMs))
            .putString(KEY_LAST_EXPORT_STATUS, ExportStatus.IN_PROGRESS.name)
            .putString(KEY_LAST_EXPORT_FILE, safFile)
            .apply()
    }

    fun markExportSuccess(
        context: Context,
        safFile: String?,
        integrityCheck: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SUCCESSFUL_EXPORT_MS, nowMs)
            .putString(KEY_LAST_SUCCESSFUL_EXPORT_UTC, utcZ(nowMs))
            .putString(KEY_LAST_EXPORT_STATUS, ExportStatus.SUCCESS.name)
            .putString(KEY_LAST_EXPORT_ERROR, null)
            .putString(KEY_LAST_EXPORT_FILE, safFile)
            .putString(KEY_LAST_INTEGRITY_CHECK, integrityCheck)
            .apply()
    }

    fun markExportFailure(
        context: Context,
        error: String,
        safFile: String? = null,
        integrityCheck: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_EXPORT_ATTEMPT_MS, nowMs)
            .putString(KEY_LAST_EXPORT_ATTEMPT_UTC, utcZ(nowMs))
            .putString(KEY_LAST_EXPORT_STATUS, ExportStatus.FAILED.name)
            .putString(KEY_LAST_EXPORT_ERROR, error.take(500))
            .putString(KEY_LAST_EXPORT_FILE, safFile)
            .putString(KEY_LAST_INTEGRITY_CHECK, integrityCheck)
            .apply()
    }

    fun utcZ(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))
}
