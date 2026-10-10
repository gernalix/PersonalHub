package com.example.multitimetracker.api

import android.content.Context
import androidx.room.withTransaction
import com.example.multitimetracker.persistence.SnapshotStore
import com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity
import com.gernalix.personalhub.contracts.database.SinceWhenMigrationState
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Legacy compatibility boundary only: no Timer initialization or UI/lifecycle hooks. */
object LegacySinceWhenMigrationApi {
    /** One-time, transactional copy of the legacy Timer snapshot into PH's global counter table. */
    suspend fun ensureMigrated(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val database = PersonalHubDatabase.get(app)
        val dao = database.sinceWhenCounterDao()
        val migrationKey = "timer.life_periods.to_since_when.v1"
        if (dao.migrationComplete(migrationKey)) return@withContext
        val periods = SnapshotStore.load(app)?.lifePeriods.orEmpty()
        val migratedAt = System.currentTimeMillis()
        database.withTransaction {
            if (dao.migrationComplete(migrationKey)) return@withTransaction
            periods.forEach { period ->
                val counter = SinceWhenCounterEntity(
                    id = period.id,
                    title = period.title,
                    description = period.description,
                    initialTimestamp = period.startMs,
                    endTimestamp = period.endMs,
                    colorArgb = period.colorArgb,
                    displayUnitsJson = JSONArray().apply { period.displayUnits.forEach { put(it.name) } }.toString(),
                    legacyTagIdsJson = JSONArray().apply { period.tagIds.sorted().forEach(::put) }.toString(),
                    createdAt = migratedAt,
                )
                if (dao.insertLegacy(counter) == -1L) {
                    // An autonomous counter may have claimed this old Timer ID before import.
                    // Keep that canonical row and import the legacy content under a free DB ID.
                    dao.insert(counter.copy(id = 0))
                }
            }
            dao.markMigrationComplete(SinceWhenMigrationState(migrationKey, migratedAt))
        }
    }
}
