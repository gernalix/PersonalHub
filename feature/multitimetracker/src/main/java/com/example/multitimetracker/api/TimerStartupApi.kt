package com.example.multitimetracker.api

import android.content.Context
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.persistence.LegacyTagSessionRepair
import com.example.multitimetracker.persistence.SnapshotStore
import com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity
import com.gernalix.personalhub.contracts.database.SinceWhenMigrationState
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public host entrypoint for Timer startup work. */
object TimerStartupApi {
    private val firstUsableScreen = CountDownLatch(1)

    fun awaitFirstUsableScreen(): Boolean = firstUsableScreen.await(15, TimeUnit.SECONDS)

    fun signalFirstUsableScreen() = firstUsableScreen.countDown()

    fun applicationOnCreate() = StartupPerfTrace.applicationOnCreate()

    /** One-time, transactional copy of the legacy Timer snapshot into PH's global counter table. */
    suspend fun ensureLegacySinceWhenMigrated(context: Context) = withContext(Dispatchers.IO) {
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
                dao.insertLegacy(
                    SinceWhenCounterEntity(
                        id = period.id,
                        title = period.title,
                        description = period.description,
                        initialTimestamp = period.startMs,
                        endTimestamp = period.endMs,
                        colorArgb = period.colorArgb,
                        displayUnitsJson = JSONArray().apply { period.displayUnits.forEach { put(it.name) } }.toString(),
                        legacyTagIdsJson = JSONArray().apply { period.tagIds.sorted().forEach(::put) }.toString(),
                        createdAt = migratedAt,
                    ),
                )
            }
            dao.markMigrationComplete(SinceWhenMigrationState(migrationKey, migratedAt))
        }
    }

    /**
     * Heals tag/session edges omitted by older session-only bootstraps.
     *
     * The host runs this off the UI thread after interrupted DB-import recovery.
     * The repair is additive/idempotent and uses a database transaction, so it
     * does not need to delay the first Activity/frame. CriticalDataGuard remains
     * authoritative if a legacy row cannot be mapped.
     */
    fun repairLegacyTagSessionsAfterHostDatabaseRecovery(context: Context) {
        LegacyTagSessionRepair.repairIfNeeded(context.applicationContext)
    }
}
