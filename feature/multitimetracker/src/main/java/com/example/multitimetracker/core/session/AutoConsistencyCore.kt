// v391
package com.example.multitimetracker.core.session

import android.content.Context
import com.example.multitimetracker.persistence.AutoConsistencyEngine

/**
 * FEATURE CAPSULE: Auto Consistency Core — START
 *
 * High-level bridge for keeping legacy snapshot-derived fields consistent with
 * the session-only tables (sessions + session_tags).
 *
 * Why this exists:
 * - MainViewModel/UI should not talk to persistence internals directly.
 *
 * FEATURE CAPSULE: Auto Consistency Core — END
 */
object AutoConsistencyCore {
    data class Result(
        val ran: Boolean,
        val changed: Boolean,
        val reason: String
    )

    fun runIfNeeded(context: Context, nowMs: Long, currentPatch: Long): Result {
        val r = AutoConsistencyEngine.runIfNeeded(
            context = context,
            nowMs = nowMs,
            currentPatch = currentPatch
        )
        return Result(ran = r.ran, changed = r.changed, reason = r.reason)
    }
}
