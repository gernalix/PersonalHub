package com.gernalix.personalhub.core.database.capsules.mutationevents

import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.HubActivityStatus
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryStore

sealed interface MutationUndoTarget {
    data class Activity(val activityId: String) : MutationUndoTarget
    data class Git(val gitEventId: String) : MutationUndoTarget
}

/** Never offer a low-level undo when it would reverse only part of a logical action. */
object MutationUndoResolver {
    fun resolve(db: SupportSQLiteDatabase, transactionId: String, gitEnabled: Boolean): MutationUndoTarget? {
        val events = MutationEventStore.byTransaction(db, transactionId)
        if (events.isEmpty() || events.any { it.actorType != "user" }) return null
        if (gitEnabled) {
            val rows = GitHistoryStore.byGroup(db, transactionId)
            if (rows.isEmpty() || rows.any { it.revertedBy != null }) return null
            return MutationUndoTarget.Git(rows.first().id)
        }
        if (events.size != 1) return null
        return db.query(
            "SELECT id,reversible,status,reverted_at,reverts_activity_id FROM hub_activity_log WHERE group_id=?",
            arrayOf(transactionId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getString(0)
            val safe = cursor.getInt(1) == 1 && cursor.getString(2) == HubActivityStatus.ACTIVE &&
                cursor.isNull(3) && cursor.isNull(4) && !cursor.moveToNext()
            if (safe) MutationUndoTarget.Activity(id) else null
        }
    }
}
