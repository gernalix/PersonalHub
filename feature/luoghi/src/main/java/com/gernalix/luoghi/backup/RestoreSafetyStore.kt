package com.gernalix.luoghi.backup

import android.content.Context

object RestoreSafetyStore {
    private const val PREFS = "luoghi_restore_safety"
    private const val KEY_AUTOEXPORT_PROTECTED = "autoexport_protected"

    fun isAutoExportProtected(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOEXPORT_PROTECTED, false)

    fun setAutoExportProtected(context: Context, protected: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOEXPORT_PROTECTED, protected)
            .commit()
    }
}
