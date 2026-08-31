package com.example.multitimetracker.persistence

import android.content.Context

/**
 * Lightweight counters used by Diagnostics to spot NOW/Chronology sync churn.
 *
 * Persisted in SharedPreferences (small + cheap), so it survives app restarts.
 */
object SyncCountersStore {
    private const val PREFS = "mt_sync_counters"

    private fun sp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bump(context: Context, action: String, nowMs: Long) {
        val safe = action.trim().take(80)
        val s = sp(context)
        val countKey = "count_$safe"
        val lastKey = "last_$safe"
        val totalKey = "count_total"
        val firstKey = "first_ms"

        s.edit()
            .putLong(countKey, s.getLong(countKey, 0L) + 1L)
            .putLong(lastKey, nowMs)
            .putLong(totalKey, s.getLong(totalKey, 0L) + 1L)
            .putLong(firstKey, s.getLong(firstKey, nowMs).let { if (it == 0L) nowMs else it })
            .apply()
    }

    data class Snapshot(
        val total: Long,
        val firstMs: Long,
        val entries: List<Entry>
    ) {
        data class Entry(
            val action: String,
            val count: Long,
            val lastMs: Long
        )
    }

    /**
     * Returns up to [maxActions] actions ordered by last occurrence (descending).
     */
    fun readSnapshot(context: Context, maxActions: Int = 12): Snapshot {
        val s = sp(context)
        val all = s.all
        val total = (all["count_total"] as? Long) ?: 0L
        val firstMs = (all["first_ms"] as? Long) ?: 0L

        val lastByAction = mutableMapOf<String, Long>()
        val countByAction = mutableMapOf<String, Long>()

        for ((k, v) in all) {
            if (k.startsWith("last_")) {
                val action = k.removePrefix("last_")
                lastByAction[action] = (v as? Long) ?: 0L
            } else if (k.startsWith("count_") && k != "count_total") {
                val action = k.removePrefix("count_")
                countByAction[action] = (v as? Long) ?: 0L
            }
        }

        val entries = lastByAction.entries
            .sortedByDescending { it.value }
            .take(maxActions)
            .map { (action, lastMs) ->
                Snapshot.Entry(
                    action = action,
                    count = countByAction[action] ?: 0L,
                    lastMs = lastMs
                )
            }

        return Snapshot(total = total, firstMs = firstMs, entries = entries)
    }
}
