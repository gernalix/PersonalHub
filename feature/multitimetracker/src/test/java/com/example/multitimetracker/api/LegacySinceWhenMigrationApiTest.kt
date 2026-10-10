package com.example.multitimetracker.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacySinceWhenMigrationApiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = "timer.life_periods.to_since_when.v1"
    private val db get() = PersonalHubDatabase.get(context)
    @Before fun prepare() { PersonalHubDatabase.resetForTests(); context.deleteDatabase(PersonalHubDatabase.DB_NAME) }
    @After fun cleanup() { PersonalHubDatabase.resetForTests(); context.deleteDatabase(PersonalHubDatabase.DB_NAME) }
    private fun snapshot(value: String) { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO snapshot(id,json,saved_at_ms) VALUES(1,?,1)", arrayOf(value)) }
    private val legacy = """{"lifePeriods":[{"id":7,"title":"Legacy","description":"Kept","startMs":1790000012345,"endMs":1790100098765,"colorArgb":4288363868,"displayUnits":["HOURS","SECONDS"],"tagIds":[42,73]}]}"""

    @Test fun preciseLegacyCopyIsTransactionalAndRepeatedConcurrentCallsDoNotDuplicate() = runBlocking {
        snapshot(legacy)
        coroutineScope { listOf(async { LegacySinceWhenMigrationApi.ensureMigrated(context) }, async { LegacySinceWhenMigrationApi.ensureMigrated(context) }).awaitAll() }
        val row = db.sinceWhenCounterDao().all().single()
        assertEquals(7L, row.id)
        assertEquals(1790000012345L, row.initialTimestamp)
        assertEquals(1790100098765L, row.endTimestamp)
        assertEquals(4288363868L, row.colorArgb)
        assertEquals("Kept", row.description)
        assertEquals("[\"HOURS\",\"SECONDS\"]", row.displayUnitsJson)
        assertEquals("[42,73]", row.legacyTagIdsJson)
        assertTrue(db.sinceWhenCounterDao().migrationComplete(key))
        snapshot("malformed") // completed migration must never reload Timer data
        LegacySinceWhenMigrationApi.ensureMigrated(context)
        assertEquals(listOf(row), db.sinceWhenCounterDao().all())
    }
    @Test fun autonomousIdCollisionPreservesBothCountersAndRetriesDoNotDuplicate() = runBlocking {
        val canonical = SinceWhenCounterEntity(id = 7, title = "Autonomous", initialTimestamp = 1790000000000L, sourceEntityType = "soldi/transaction", sourceEntityId = "kept", sourceTimestampField = "occurredAt", createdAt = 1, canonicalId = java.util.UUID.randomUUID().toString())
        db.openHelper.writableDatabase.execSQL("INSERT INTO hub_entities VALUES('kept','soldi/transaction','soldi','finance_transactions','99','TOMBSTONED',1,1)")
        db.sinceWhenCounterDao().insert(canonical)
        snapshot(legacy)
        LegacySinceWhenMigrationApi.ensureMigrated(context)
        LegacySinceWhenMigrationApi.ensureMigrated(context)
        assertEquals(canonical, db.sinceWhenCounterDao().get(7))
        val imported = db.sinceWhenCounterDao().all().single { it.title == "Legacy" }
        assertNotEquals(7L, imported.id)
        assertEquals(1790000012345L, imported.initialTimestamp)
        assertEquals(2, db.sinceWhenCounterDao().all().size)
    }
    @Test fun failedLegacyLoadDoesNotMarkCompleteAndCanBeRetriedWithoutLoss() = runBlocking {
        snapshot("malformed")
        try { LegacySinceWhenMigrationApi.ensureMigrated(context); fail("Expected corrupt legacy load to fail") } catch (_: IllegalStateException) { }
        assertFalse(db.sinceWhenCounterDao().migrationComplete(key))
        assertTrue(db.sinceWhenCounterDao().all().isEmpty())
        snapshot(legacy)
        LegacySinceWhenMigrationApi.ensureMigrated(context)
        assertEquals("Legacy", db.sinceWhenCounterDao().all().single().title)
    }
}
