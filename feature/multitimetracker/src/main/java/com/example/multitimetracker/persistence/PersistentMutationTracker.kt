package com.example.multitimetracker.persistence

import android.content.Context
import com.gernalix.personalhub.core.database.HubAutoExport

/** Timer commits use the global persistent generation and serial export queue. */
object PersistentMutationTracker {
    fun record(context: Context, source: String) {
        SyncStatusStore.markDatabaseMutation(context, source, System.currentTimeMillis())
        HubAutoExport.request(context)
        com.example.multitimetracker.capsules.remotesync.RemoteSyncScheduler.requestDebounced(context)
    }
    fun requestExport(context: Context) = HubAutoExport.request(context)
}
