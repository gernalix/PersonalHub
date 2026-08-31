package com.supercontacts.app.data.repository

import android.content.Context

class HomePreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSort(): ContactHomeSortState {
        val criterion = runCatching {
            ContactHomeSort.valueOf(prefs.getString(KEY_SORT, ContactHomeSort.NAME.name) ?: ContactHomeSort.NAME.name)
        }.getOrDefault(ContactHomeSort.NAME)
        val direction = runCatching {
            ContactHomeSortDirection.valueOf(
                prefs.getString(KEY_SORT_DIRECTION, ContactHomeSortDirection.ASC.name)
                    ?: ContactHomeSortDirection.ASC.name,
            )
        }.getOrDefault(ContactHomeSortDirection.ASC)
        return ContactHomeSortState(criterion = criterion, direction = direction)
    }

    fun writeSort(sort: ContactHomeSortState) {
        prefs.edit()
            .putString(KEY_SORT, sort.criterion.name)
            .putString(KEY_SORT_DIRECTION, sort.direction.name)
            .apply()
    }

    fun readShowAddedEdited(): Boolean =
        prefs.getBoolean(KEY_SHOW_ADDED_EDITED, true)

    fun writeShowAddedEdited(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ADDED_EDITED, show).apply()
    }

    private companion object {
        const val PREFS_NAME = "supercontacts_home"
        const val KEY_SORT = "sort"
        const val KEY_SORT_DIRECTION = "sort_direction"
        const val KEY_SHOW_ADDED_EDITED = "show_added_edited"
    }
}
