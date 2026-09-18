package com.example.multitimetracker.persistence

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Centralized lightweight invariant logging to detect NOW/Chronology desync early.
 *
 * Keep it cheap and always-on so runtime desynchronization remains visible in Logcat.
 */
@OptIn(com.example.multitimetracker.util.CapsuleWriteApi::class)
object SyncInvariants {
    private const val TAG = "MTT_SYNC"

    fun log(
        context: Context,
        action: String,
        summary: String,
        payload: JSONObject? = null
    ) {
        Log.i(TAG, "$action: $summary")
    }
}
