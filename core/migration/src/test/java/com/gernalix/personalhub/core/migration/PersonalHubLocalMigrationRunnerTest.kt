package com.gernalix.personalhub.core.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonalHubLocalMigrationRunnerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun cleanBefore() {
        cleanDatabases()
    }

    @After
    fun cleanAfter() {
        cleanDatabases()
    }

    @Test
    fun runScansLocalFeatureDatabasesAndRecordsVerifiedMappings() {
        context.openOrCreateDatabase("sostanze.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE substances(id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT)")
            db.execSQL("INSERT INTO substances(id) VALUES (1), (2)")
            db.execSQL("INSERT INTO settings(key, value) VALUES ('locale', 'it')")
        }
        context.openOrCreateDatabase("mtt_remote_sync.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE sync_meta(key TEXT PRIMARY KEY, long_value INTEGER)")
            db.execSQL("CREATE TABLE sync_shadow(sync_id TEXT PRIMARY KEY, fingerprint TEXT, row_json TEXT)")
            db.execSQL("INSERT INTO sync_meta(key, long_value) VALUES ('revision', 3)")
            db.execSQL("INSERT INTO sync_shadow(sync_id, fingerprint, row_json) VALUES ('s1', 'f1', '{}')")
        }

        val result = PersonalHubLocalMigrationRunner(context).run()

        assertTrue(result.report.passed)
        assertEquals(5, result.report.sourceCount)
        assertEquals(5, result.report.mappingCount)
        assertEquals("ok", result.mappingDatabaseIntegrity)
        assertEquals(
            mapOf(
                "multitimetracker.mtt_remote_sync.sync_meta" to 1,
                "multitimetracker.mtt_remote_sync.sync_shadow" to 1,
                "sostanze.settings" to 1,
                "sostanze.substances" to 2,
            ),
            result.mappingCountsBySourceTable,
        )
    }

    @Test
    fun runIsIdempotentForTheSameLocalDatabases() {
        context.openOrCreateDatabase("wordpulse.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO sessions(id) VALUES ('session-1')")
        }
        val runner = PersonalHubLocalMigrationRunner(context)

        runner.run()
        val secondRun = runner.run()

        assertEquals(1, secondRun.report.sourceCount)
        assertEquals(mapOf("wordpulse.sessions" to 1), secondRun.mappingCountsBySourceTable)
    }

    private fun cleanDatabases() {
        listOf(
            "luoghi.db",
            "multitimer.db",
            "mtt_remote_sync.db",
            "sostanze.db",
            "super_contacts.db",
            "wordpulse.db",
            MigrationMappingStore.DB_NAME,
        ).forEach(context::deleteDatabase)
    }
}

private inline fun <T> SQLiteDatabase.use(block: (SQLiteDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
