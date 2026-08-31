package com.example.multitimetracker.capsules.remotesync

import android.content.Context

internal object RemoteSyncStatusStore {
    private const val PREFS = "mtt_remote_sync_status"

    fun markSuccess(context: Context, pending: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_success_at_ms", System.currentTimeMillis())
            .putInt("pending_count", pending)
            .remove("last_failure_class")
            .apply()
    }

    fun markFailure(context: Context, failureClass: String, pending: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_failure_at_ms", System.currentTimeMillis())
            .putString("last_failure_class", failureClass.take(60))
            .putInt("pending_count", pending)
            .apply()
    }
}
