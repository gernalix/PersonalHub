package com.gernalix.sostanze.widget

import androidx.core.content.edit
import android.content.Context
import com.gernalix.personalhub.core.database.DatabaseProfiles

object SubstancesWidgetPrefs {
    private const val PREFS = "substances_widget_prefs"

    fun save(context: Context, appWidgetId: Int, substanceId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putLong(key(appWidgetId), substanceId)
                putString(profileKey(appWidgetId), DatabaseProfiles.activeProfileId(context))
            }
    }

    fun read(context: Context, appWidgetId: Int): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getLong(key(appWidgetId), Long.MIN_VALUE)
        if (value == Long.MIN_VALUE) return null
        val active = DatabaseProfiles.activeProfileId(context)
        val owner = prefs.getString(profileKey(appWidgetId), null) ?: run {
            // Legacy widgets resolved their target in the active database. Preserve
            // that behavior when adopting them, then prevent later cross-profile taps.
            prefs.edit { putString(profileKey(appWidgetId), active) }
            active
        }
        return value.takeIf { owner == active }
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            appWidgetIds.forEach { remove(key(it)); remove(profileKey(it)) }
        }
    }

    fun restore(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        if (oldWidgetIds.size != newWidgetIds.size) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Snapshot before writing: Android may restore IDs which overlap an old mapping.
        val mappings = oldWidgetIds.zip(newWidgetIds).mapNotNull { (old, new) ->
            val target = prefs.getLong(key(old), Long.MIN_VALUE)
            if (target == Long.MIN_VALUE) null else Triple(new, target, prefs.getString(profileKey(old), null))
        }
        prefs.edit {
            oldWidgetIds.filterNot { it in newWidgetIds }.forEach {
                remove(key(it)); remove(profileKey(it))
            }
            mappings.forEach { (id, target, profile) ->
                putLong(key(id), target)
                if (profile == null) remove(profileKey(id)) else putString(profileKey(id), profile)
            }
        }
    }

    private fun key(appWidgetId: Int): String = "substance_$appWidgetId"
    private fun profileKey(appWidgetId: Int): String = "profile_$appWidgetId"
}
