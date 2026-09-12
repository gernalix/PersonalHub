// v391
package com.example.multitimetracker.core.session

import android.content.Context
import com.example.multitimetracker.persistence.AutoConsistencyEngine
import com.example.multitimetracker.persistence.LegacyTagSessionRepair

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
        // Old session-only bootstraps could omit historical tag edges or, on
        // partially migrated devices, the closed session row itself. Repair
        // additively before regular auto-consistency; CriticalDataGuard remains
        // authoritative for anything the repair cannot map safely.
        val repair = LegacyTagSessionRepair.repairIfNeeded(context)

        val r = AutoConsistencyEngine.runIfNeeded(
            context = context,
            nowMs = nowMs,
            currentPatch = currentPatch
        )

        val repairReason = when {
            repair.error != null -> "legacy tag-session repair error=${repair.error}"
            repair.createdSessions > 0 || repair.inserted > 0 ->
                "legacy tag-session repair createdSessions=${repair.createdSessions} " +
                    "insertedEdges=${repair.inserted}/${repair.examined}"
            repair.ambiguousOrMissingSession > 0 || repair.missingTag > 0 ->
                "legacy tag-session repair unresolved=${repair.ambiguousOrMissingSession} missingTag=${repair.missingTag}"
            repair.examined > 0 -> "legacy tag-session repair already consistent"
            else -> null
        }

        return Result(
            ran = r.ran || repair.examined > 0,
            changed = r.changed || repair.changed,
            reason = listOfNotNull(repairReason, r.reason).joinToString("; ")
        )
    }
}
