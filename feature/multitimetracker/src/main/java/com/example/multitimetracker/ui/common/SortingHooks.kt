// v322
package com.example.multitimetracker.ui.common

object SortingHooks {

    fun <T> sortDistinctById(
        list: List<T>,
        idSelector: (T) -> Any?
    ): List<T> {
        return list
            .distinctBy { idSelector(it) }
            .sortedBy { idSelector(it)?.toString() }
    }
}
