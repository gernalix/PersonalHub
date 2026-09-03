package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubAutoExportDurabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var scheduler: RecordingScheduler

    @Before
    fun setUp() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        DatabaseVault.preferences(context).edit().clear().commit()
        scheduler = RecordingScheduler()
        HubAutoExport.setSchedulerForTests(scheduler)
    }

    @After
    fun tearDown() {
        PersonalHubDatabase.resetForTests()
        HubAutoExport.resetSchedulerForTests()
        DatabaseVault.preferences(context).edit().clear().commit()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @Test
    fun mutationWithConfiguredFolderCreatesDurableWorkAfterGenerationChanges() {
        configureFolderPreference()
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase

        db.beginTransaction()
        assertEquals(0, scheduler.autoExportRequests)
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)",
                arrayOf("durability_test", "{}"),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        assertEquals(1, scheduler.autoExportRequests)
        assertTrue(DatabaseVault.autoExportStatus(context).stale)
    }

    @Test
    fun internalSyncBookkeepingDoesNotScheduleExportWhenGenerationIsClean() {
        configureFolderPreference()
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val current = DatabaseVault.currentGeneration(context)
        DatabaseVault.preferences(context).edit().putLong("exported_generation", current).commit()
        scheduler.autoExportRequests = 0

        db.execSQL(
            "INSERT OR REPLACE INTO hub_sync_known(table_name,row_key) VALUES(?,?)",
            arrayOf("contacts", "already-synced"),
        )

        assertEquals(current, DatabaseVault.currentGeneration(context))
        assertEquals(0, scheduler.autoExportRequests)
    }

    @Test
    fun missingSafFolderDoesNotCreateUnrecoverableWork() {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase

        db.execSQL(
            "INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)",
            arrayOf("no_folder_test", "{}"),
        )

        assertEquals(0, scheduler.autoExportRequests)
        assertFalse(DatabaseVault.autoExportStatus(context).folderConfigured)
    }

    @Test
    fun startInstallsPeriodicRecoveryAndDirtyCheckCanQueueOneTimeWork() {
        configureFolderPreference()
        PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)",
            arrayOf("start_test", "{}"),
        )
        scheduler.autoExportRequests = 0

        HubAutoExport.start(context)
        HubAutoExport.request(context)

        assertEquals(1, scheduler.periodicRecoveryRequests)
        assertEquals(1, scheduler.autoExportRequests)
    }

    @Test
    fun requestIfDirtySkipsCleanStateAndQueuesDirtyState() {
        configureFolderPreference()
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val current = DatabaseVault.currentGeneration(context)
        DatabaseVault.preferences(context).edit().putLong("exported_generation", current).commit()

        HubAutoExport.requestIfDirty(context)
        assertEquals(0, scheduler.autoExportRequests)

        db.execSQL(
            "INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)",
            arrayOf("dirty_request_test", "{}"),
        )

        assertEquals(1, scheduler.autoExportRequests)
    }

    @Test
    fun enqueueFailureIsPersistedForVisibility() {
        configureFolderPreference()
        scheduler.failure = IllegalStateException("provider unavailable")

        val error = runCatching { HubAutoExport.request(context) }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("provider unavailable", DatabaseVault.error(context))
        assertEquals("provider unavailable", DatabaseVault.autoExportStatus(context).lastError)
    }

    @Test
    fun exportedGenerationOnlyChangesThroughSuccessfulExportMarker() {
        configureFolderPreference()
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)",
            arrayOf("generation_test", "{}"),
        )
        val current = DatabaseVault.currentGeneration(context)

        assertEquals(-1, DatabaseVault.exportedGeneration(context))
        assertTrue(DatabaseVault.autoExportStatus(context).stale)

        DatabaseVault.preferences(context).edit()
            .putLong("exported_generation", current)
            .putLong("exported_at", 1234L)
            .remove("error")
            .commit()

        val status = DatabaseVault.autoExportStatus(context)
        assertEquals(current, status.exportedGeneration)
        assertFalse(status.stale)
        assertEquals(1234L, status.lastSuccessfulExportAt)
    }

    private fun configureFolderPreference() {
        DatabaseVault.preferences(context).edit()
            .putString("tree_uri", "content://personalhub-test/tree")
            .putLong("exported_generation", -1)
            .commit()
    }

    private class RecordingScheduler : HubAutoExport.Scheduler {
        var periodicRecoveryRequests = 0
        var autoExportRequests = 0
        var failure: RuntimeException? = null

        override fun cancelLegacyWork(context: Context) = Unit

        override fun enqueuePeriodicRecovery(context: Context) {
            periodicRecoveryRequests += 1
        }

        override fun enqueueAutoExport(context: Context) {
            failure?.let { throw it }
            autoExportRequests += 1
        }
    }
}
