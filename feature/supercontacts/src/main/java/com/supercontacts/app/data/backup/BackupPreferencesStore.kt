package com.supercontacts.app.data.backup

import android.content.Context

internal class BackupPreferencesStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readFolderUri(): String? = prefs.getString(KEY_FOLDER_URI, null)

    fun writeFolderUri(uri: String?) {
        prefs.edit().putString(KEY_FOLDER_URI, uri).apply()
    }

    fun clearFolderUri() {
        prefs.edit().remove(KEY_FOLDER_URI).apply()
    }

    fun readAutoExportEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_EXPORT_ENABLED, true)

    fun writeAutoExportEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_EXPORT_ENABLED, enabled).apply()
    }

    fun readLastExportAt(): Long? =
        prefs.getLong(KEY_LAST_EXPORT_AT, NO_TIMESTAMP).takeIf { it != NO_TIMESTAMP }

    fun writeLastExportAt(timestamp: Long?) {
        prefs.edit().putLong(KEY_LAST_EXPORT_AT, timestamp ?: NO_TIMESTAMP).apply()
    }

    fun readLastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    fun writeLastError(error: String?) {
        prefs.edit().putString(KEY_LAST_ERROR, error).apply()
    }

    private companion object {
        private const val PREFS_NAME = "supercontacts_backup"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_AUTO_EXPORT_ENABLED = "auto_export_enabled"
        private const val KEY_LAST_EXPORT_AT = "last_export_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val NO_TIMESTAMP = -1L
    }
}
