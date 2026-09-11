package com.gernalix.sostanze.widget

import android.content.Context

object SubstancesWidgetPrefs {
    private const val PREFS = "substances_widget_prefs"

    fun save(context: Context, appWidgetId: Int, substanceId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(key(appWidgetId), substanceId)
            .apply()
    }

    fun read(context: Context, appWidgetId: Int): Long? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key(appWidgetId), Long.MIN_VALUE)
        return value.takeIf { it != Long.MIN_VALUE }
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    private fun key(appWidgetId: Int): String = "substance_$appWidgetId"
}
