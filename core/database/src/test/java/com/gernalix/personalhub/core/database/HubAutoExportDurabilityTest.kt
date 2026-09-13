package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.test.core.app.ApplicationProvider
import com.wordpulse.app.data.WordEntry
import com.wordpulse.app.data.WordSession
import kotlinx.coroutines.runBlocking
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
import java.io.File

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
        DatabaseVault.setTransferHooksForTests(null)
        DatabaseVault.setExportPublisherFactoryForTests(null)
        DatabaseVault.setDirectorySyncForTests { }
    }

    @After
    fun tearDown() {
        PersonalHubDatabase.resetForTests()
        HubAutoExport.resetSchedulerForTests()
        DatabaseVault.setTransferHooksForTests(null)
        DatabaseVault.setExportPublisherFactoryForTests(null)
        DatabaseVault.setDirectorySyncForTests(null)
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
        scheduler.activeAutoExports = 0

        HubAutoExport.start(context)

        assertEquals(1, scheduler.periodicRecoveryRequests)
        assertEquals(1, scheduler.autoExportRequests)
        assertEquals(1, scheduler.activeAutoExports)
        assertEquals(ExistingWorkPolicy.REPLACE, scheduler.lastPolicy)
    }

    @Test
    fun rapidWordPulseWritesCoalesceAndWorkerExportsLatestGeneration() = runBlocking {
        configureFolderPreference()
        val database = PersonalHubDatabase.get(context)
        val dao = database.wordPulseDao()
        val before = DatabaseVault.currentGeneration(context)
        dao.insertSession(WordSession(id = "rapid-session", startedAtUtcMs = 1L))
        repeat(20) { index ->
            dao.insertWord(
                WordEntry(
                    originalWord = "rapid$index",
                    normalizedWord = "rapid$index",
                    createdAtUtcMs = index + 2L,
                    sessionId = "rapid-session",
                )
            )
        }
        val latest = DatabaseVault.currentGeneration(context)
        val publisher = RecordingExportPublisher()
        DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }

        assertEquals(before + 22, latest)
        assertEquals(1, scheduler.activeAutoExports)
        assertEquals(ExistingWorkPolicy.REPLACE, scheduler.lastPolicy)
        assertTrue(HubAutoExport.exportUntilClean(context) { false })
        assertEquals(latest, DatabaseVault.exportedGeneration(context))
        assertFalse(HubAutoExport.dirty(context))
        val exported = File(context.cacheDir, "rapid-wordpulse-export.db")
        exported.writeBytes(requireNotNull(publisher.files[PersonalHubDatabase.DB_NAME]))
        try {
            assertEquals(latest, DatabaseVault.validate(context, exported))
        } finally {
            exported.delete()
        }
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

    @Test
    fun validInterruptedImportRollsBackAndRetiresMarker() {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.execSQL("INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)", arrayOf("rollback_test", "{\"value\":\"before\"}"))
        val backup = File(context.getDatabasePath(PersonalHubDatabase.DB_NAME).parentFile, "personalhub-pre-import-rollback.db")
        assertTrue(DatabaseVault.backupCurrent(context).renameTo(backup))
        db.execSQL("UPDATE hub_preferences SET json=? WHERE namespace=?", arrayOf("{\"value\":\"after\"}", "rollback_test"))
        DatabaseVault.writeImportMarkerForTests(context, backup)
        PersonalHubDatabase.resetForTests()

        DatabaseVault.recoverInterruptedImport(context)

        val restored = PersonalHubDatabase.get(context).openHelper.readableDatabase
            .query("SELECT json FROM hub_preferences WHERE namespace=?", arrayOf("rollback_test"))
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        assertEquals("{\"value\":\"before\"}", restored)
        assertFalse(File(context.filesDir, "personalhub-import.pending").exists())
        assertFalse(backup.exists())
    }

    @Test
    fun damagedImportMarkersDoNotCrashOrDeleteRecoveryCopies() {
        val backup = DatabaseVault.backupCurrent(context)
        val marker = File(context.filesDir, "personalhub-import.pending")
        marker.writeText("")

        DatabaseVault.recoverInterruptedImport(context)

        assertTrue(backup.exists())
        assertFalse(marker.exists())
        assertTrue(File(context.filesDir, "personalhub-import.pending.damaged").exists())
        assertEquals(
            "Invalid interrupted import marker was preserved; recovery backups were preserved",
            DatabaseVault.error(context),
        )

        File(context.filesDir, "personalhub-import.pending.damaged").delete()
        marker.writeText("/not/a/valid/backup")

        DatabaseVault.recoverInterruptedImport(context)

        assertTrue(backup.exists())
        assertFalse(marker.exists())
        assertTrue(File(context.filesDir, "personalhub-import.pending.damaged").exists())
    }

    @Test
    fun importMarkerPublicationDoesNotExposePartialFinalMarker() {
        val backup = DatabaseVault.backupCurrent(context)
        DatabaseVault.setTransferHooksForTests(object : DatabaseVault.TransferHooks {
            override fun beforeImportMarkerPublish(temp: File, final: File) {
                throw IllegalStateException("interrupted before marker publish")
            }
        })

        val error = runCatching { DatabaseVault.writeImportMarkerForTests(context, backup) }.exceptionOrNull()

        assertNotNull(error)
        assertFalse(File(context.filesDir, "personalhub-import.pending").exists())
        assertTrue(context.filesDir.listFiles().orEmpty().none { it.name.startsWith("personalhub-import.pending.") && it.name.endsWith(".tmp") })
    }

    @Test
    fun canonicalWriteFailureKeepsValidBackupWithoutMarkingSuccess() {
        configureFolderPreference()
        val oldSnapshot = DatabaseVault.backupCurrent(context)
        val oldBytes = oldSnapshot.readBytes()
        oldSnapshot.delete()
        PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)",
            arrayOf("failed_publish_test", "{}"),
        )
        val publisher = RecordingExportPublisher(failCanonicalWrite = true)
        publisher.files[PersonalHubDatabase.DB_NAME] = oldBytes
        DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }

        val error = runCatching { DatabaseVault.exportNow(context) }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(oldBytes.toList(), requireNotNull(publisher.files["personalhub.db.bak"]).toList())
        assertEquals(-1, DatabaseVault.exportedGeneration(context))
        assertEquals("simulated canonical write failure", DatabaseVault.error(context))
    }

    @Test
    fun canonicalReadbackFailureCannotAdvanceSuccessMarker() {
        configureFolderPreference()
        val publisher = RecordingExportPublisher(corruptCanonicalReadback = true)
        DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }

        val error = runCatching { DatabaseVault.exportNow(context) }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("Invalid SQLite file", DatabaseVault.error(context))
        assertEquals(-1, DatabaseVault.exportedGeneration(context))
    }

    @Test
    fun providerRenamedFirstCanonicalIsRejected() {
        configureFolderPreference()
        val publisher = RecordingExportPublisher(createdCanonicalName = "personalhub (1).db")
        DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }

        val error = runCatching { DatabaseVault.exportNow(context) }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(-1, DatabaseVault.exportedGeneration(context))
        assertFalse(publisher.files.containsKey("personalhub (1).db"))
        assertEquals("Provider created personalhub (1).db instead of personalhub.db", DatabaseVault.error(context))
    }

    @Test
    fun invalidCanonicalIsRegeneratedWithoutOverwritingValidBackup() {
        configureFolderPreference()
        val oldSnapshot = DatabaseVault.backupCurrent(context)
        val oldBytes = oldSnapshot.readBytes()
        oldSnapshot.delete()
        PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)",
            arrayOf("recovery_test", "{}"),
        )
        val current = DatabaseVault.currentGeneration(context)
        val publisher = RecordingExportPublisher()
        publisher.files[PersonalHubDatabase.DB_NAME] = byteArrayOf(0)
        publisher.files["personalhub.db.bak"] = oldBytes
        DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }

        assertTrue(DatabaseVault.exportNow(context))

        assertEquals(current, DatabaseVault.exportedGeneration(context))
        assertEquals(oldBytes.toList(), requireNotNull(publisher.files["personalhub.db.bak"]).toList())
        val exported = File(context.cacheDir, "recovered-canonical.db")
        exported.writeBytes(requireNotNull(publisher.files[PersonalHubDatabase.DB_NAME]))
        try {
            assertEquals(current, DatabaseVault.validate(context, exported))
        } finally {
            exported.delete()
        }
    }

    @Test
    fun secondExportUsesPersistedDocumentIdentityWithoutDirectoryListing() {
        configureFolderPreference()
        val publisher = RecordingExportPublisher()
        DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }
        assertTrue(DatabaseVault.exportNow(context))
        assertEquals(PersonalHubDatabase.DB_NAME, DatabaseVault.preferences(context).getString(DatabaseVault.CANONICAL_DOCUMENT_URI, null))

        publisher.listingsVisible = false
        PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)",
            arrayOf("second_identity_export", "{}"),
        )
        val latest = DatabaseVault.currentGeneration(context)

        assertTrue(DatabaseVault.exportNow(context))
        assertEquals(latest, DatabaseVault.exportedGeneration(context))
        assertEquals(PersonalHubDatabase.DB_NAME, DatabaseVault.preferences(context).getString(DatabaseVault.CANONICAL_DOCUMENT_URI, null))
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
        var activeAutoExports = 0
        var lastPolicy: ExistingWorkPolicy? = null
        var failure: RuntimeException? = null

        override fun cancelLegacyWork(context: Context) = Unit

        override fun enqueuePeriodicRecovery(context: Context) {
            periodicRecoveryRequests += 1
        }

        override fun enqueueAutoExport(context: Context, policy: ExistingWorkPolicy) {
            failure?.let { throw it }
            autoExportRequests += 1
            lastPolicy = policy
            if (policy == ExistingWorkPolicy.REPLACE || activeAutoExports == 0) activeAutoExports = 1
        }
    }

    private class RecordingExportPublisher(
        private val failCanonicalWrite: Boolean = false,
        private val corruptCanonicalReadback: Boolean = false,
        private val createdCanonicalName: String? = null,
    ) : DatabaseVault.ExportPublisher {
        val files = linkedMapOf<String, ByteArray>()
        var listingsVisible = true

        override fun create(name: String): DatabaseVault.ExportFile {
            val actualName = if (name == PersonalHubDatabase.DB_NAME) createdCanonicalName ?: name else name
            files[actualName] = ByteArray(0)
            return RecordingExportFile(this, actualName)
        }

        override fun open(identity: String): DatabaseVault.ExportFile? =
            if (files.containsKey(identity)) RecordingExportFile(this, identity) else null

        override fun find(name: String): DatabaseVault.ExportFile? =
            if (listingsVisible && files.containsKey(name)) RecordingExportFile(this, name) else null

        override fun writeFrom(source: File, target: DatabaseVault.ExportFile) {
            if (target.name == PersonalHubDatabase.DB_NAME && failCanonicalWrite) {
                files[PersonalHubDatabase.DB_NAME] = byteArrayOf(0)
                error("simulated canonical write failure")
            }
            files[requireNotNull(target.name)] = source.readBytes()
        }

        override fun readTo(source: DatabaseVault.ExportFile, target: File) {
            target.outputStream().use { out ->
                if (source.name == PersonalHubDatabase.DB_NAME && corruptCanonicalReadback) {
                    out.write(byteArrayOf(0))
                } else {
                    out.write(requireNotNull(files[requireNotNull(source.name)]))
                }
                out.fd.sync()
            }
        }

        private class RecordingExportFile(
            private val publisher: RecordingExportPublisher,
            private var currentName: String,
        ) : DatabaseVault.ExportFile {
            override val identity: String get() = currentName
            override val name: String? get() = currentName

            override fun delete(): Boolean {
                publisher.files.remove(currentName)
                return true
            }
        }
    }
}
