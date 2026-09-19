package com.example.multitimetracker.persistence

import android.content.Context
import com.gernalix.personalhub.core.database.HubAutoExport

/** Timer commits use the global persistent generation and serial export queue. */
object PersistentMutationTracker {
    @Suppress("UNUSED_PARAMETER")
    fun record(context: Context, source: String) {
        HubAutoExport.request(context)
    }
    fun requestExport(context: Context) = HubAutoExport.request(context)
}
