package com.gernalix.luoghi.backup

import androidx.room.withTransaction
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.LuoghiSnapshot

class SnapshotRestorer(
    private val database: LuoghiDatabase,
    private val verifier: (expected: LuoghiSnapshot, actual: LuoghiSnapshot) -> Unit = { expected, actual ->
        check(expected == actual) { "Restored database does not match the validated backup" }
    },
) {
    suspend fun replaceAtomically(snapshot: LuoghiSnapshot): LuoghiSnapshot = database.withTransaction {
        database.placeDao().replaceSnapshot(snapshot)
        val actual = database.placeDao().readSnapshot()
        verifier(snapshot, actual)
        database.query("PRAGMA foreign_key_check", null).use { cursor ->
            check(!cursor.moveToFirst()) { "Foreign key verification failed after restore" }
        }
        actual
    }
}
