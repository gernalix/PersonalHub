package com.gernalix.personalhub

import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import com.gernalix.personalhub.core.database.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Runs only on an emulator with an imported real-data copy and an explicit SAF test folder. */
@RunWith(AndroidJUnit4::class)
class GlobalDatabaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private class CountingExportScheduler : HubAutoExport.Scheduler {
        val cancelledLegacy = AtomicInteger(0)
        val recovery = AtomicInteger(0)
        val export = AtomicInteger(0)
        override fun cancelLegacyWork(context: Context) { cancelledLegacy.incrementAndGet() }
        override fun enqueuePeriodicRecovery(context: Context) { recovery.incrementAndGet() }
        override fun enqueueAutoExport(context: Context, policy: ExistingWorkPolicy) { export.incrementAndGet() }
    }
    private class MemoryExportPublisher : DatabaseVault.ExportPublisher {
        val files = mutableMapOf<String, MemoryExportFile>()
        override fun create(name: String): DatabaseVault.ExportFile = MemoryExportFile(name, this).also { files[name] = it }
        override fun open(identity: String): DatabaseVault.ExportFile? = files[identity]
        override fun find(name: String): DatabaseVault.ExportFile? = files[name]
        override fun writeFrom(source: File, target: DatabaseVault.ExportFile) {
            (target as MemoryExportFile).bytes = source.readBytes()
        }
        override fun readTo(source: DatabaseVault.ExportFile, target: File) {
            target.writeBytes((source as MemoryExportFile).bytes)
        }
    }
    private class MemoryExportFile(private val displayName: String, private val owner: MemoryExportPublisher) : DatabaseVault.ExportFile {
        var bytes = ByteArray(0)
        override val identity: String get() = displayName
        override val name: String get() = displayName
        override fun delete(): Boolean {
            owner.files.remove(displayName)
            return true
        }
    }
    private fun requireEmulatorOnly() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone")) { "Mutation QA must run on the emulator" }
    }
    private fun requireEmulator() {
        requireEmulatorOnly()
        check(DatabaseVault.folder(context)?.contains("PersonalHubQA") == true) { "Choose the isolated PersonalHubQA SAF folder first" }
    }
    private fun exportCopy(): File {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(DatabaseVault.folder(context))))
        val file = requireNotNull(root.findFile("personalhub.db"))
        return File(context.cacheDir, "qa-readback.db").also { local ->
            context.contentResolver.openInputStream(file.uri).use { input -> local.outputStream().use { requireNotNull(input).copyTo(it) } }
        }
    }
    private fun scalar(sql: String): Long = PersonalHubDatabase.get(context).openHelper.readableDatabase.query(sql).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
    private fun generation() = scalar("SELECT generation FROM hub_generation WHERE id=1")
    @Test fun roomAndTimerWritesScheduleAndProduceExportWithoutPolling() = runBlocking {
        val scheduler = CountingExportScheduler()
        val publisher = MemoryExportPublisher()
        HubAutoExport.setSchedulerForTests(scheduler)
        DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }
        val prefs = context.getSharedPreferences("personalhub_transfer", Context.MODE_PRIVATE)
        val previousFolder = DatabaseVault.folder(context)
        val previousExportedGeneration = DatabaseVault.exportedGeneration(context)
        val previousError = DatabaseVault.error(context)
        val owner = PersonalHubDatabase.get(context)
        val db = owner.openHelper.writableDatabase
        prefs.edit().putString("tree_uri", "content://personalhub.test/export").putLong("exported_generation", generation()).remove("error").commit()
        var contactId = 0L
        try {
            owner.withTransaction {
                contactId = owner.contactsDao().insertContact(com.supercontacts.app.data.local.ContactEntity(publicId = "qa-export-trigger", createdAt = 1, updatedAt = 1))
            }
            val roomGeneration = generation()
            assertEquals(1, scheduler.export.get())
            assertTrue(DatabaseVault.exportNow(context))
            assertEquals(roomGeneration, DatabaseVault.exportedGeneration(context))
            assertTrue(publisher.files.getValue(PersonalHubDatabase.DB_NAME).bytes.isNotEmpty())
            assertEquals(setOf(PersonalHubDatabase.DB_NAME), publisher.files.keys)

            val beforeTimerExportRequests = scheduler.export.get()
            DatabasePreferences(context, "qa_timer_export_trigger").edit().putInt("value", 1).commit()
            assertTrue(scheduler.export.get() > beforeTimerExportRequests)
            assertTrue(DatabaseVault.exportNow(context))
            assertEquals(generation(), DatabaseVault.exportedGeneration(context))
            assertEquals(setOf(PersonalHubDatabase.DB_NAME), publisher.files.keys)
        } finally {
            if (contactId != 0L) db.execSQL("DELETE FROM contacts WHERE id=?", arrayOf(contactId))
            db.execSQL("DELETE FROM hub_preferences WHERE namespace='qa_timer_export_trigger'")
            prefs.edit().apply {
                if (previousFolder == null) remove("tree_uri") else putString("tree_uri", previousFolder)
                putLong("exported_generation", previousExportedGeneration)
                if (previousError == null) remove("error") else putString("error", previousError)
            }.commit()
            DatabaseVault.setExportPublisherFactoryForTests(null)
            HubAutoExport.resetSchedulerForTests()
        }
    }
    @Test fun autoExportRetiresLegacyBackupAndKeepsOnlyCanonicalFile() {
        requireEmulatorOnly()
        val publisher = MemoryExportPublisher()
        DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }
        val prefs = context.getSharedPreferences("personalhub_transfer", Context.MODE_PRIVATE)
        val previousFolder = DatabaseVault.folder(context)
        val previousBackupIdentity = prefs.getString("backup_document_uri", null)
        try {
            prefs.edit()
                .putString("tree_uri", "content://personalhub.test/export")
                .putString("backup_document_uri", "personalhub.db.bak")
                .commit()
            publisher.create("personalhub.db.bak")
            assertTrue(DatabaseVault.exportNow(context))
            assertEquals(setOf(PersonalHubDatabase.DB_NAME), publisher.files.keys)
            assertNull(prefs.getString("backup_document_uri", null))
        } finally {
            prefs.edit().apply {
                if (previousFolder == null) remove("tree_uri") else putString("tree_uri", previousFolder)
                if (previousBackupIdentity == null) remove("backup_document_uri")
                else putString("backup_document_uri", previousBackupIdentity)
            }.commit()
            DatabaseVault.setExportPublisherFactoryForTests(null)
        }
    }

    @Test fun backupCurrentUsesOneTransientFileAndCleansLegacyCopies() {
        requireEmulatorOnly()
        val databaseDir = context.getDatabasePath(PersonalHubDatabase.DB_NAME).parentFile!!
        val legacyA = File(databaseDir, "personalhub-backup-legacy-a.db").apply { writeText("legacy") }
        val legacyB = File(databaseDir, "personalhub-backup-legacy-b.db").apply { writeText("legacy") }
        val first = DatabaseVault.backupCurrent(context)
        val second = DatabaseVault.backupCurrent(context)
        try {
            assertEquals(first.canonicalPath, second.canonicalPath)
            assertEquals(context.cacheDir.canonicalPath, second.parentFile!!.canonicalPath)
            assertTrue(second.isFile)
            assertFalse(legacyA.exists())
            assertFalse(legacyB.exists())
            assertTrue(
                databaseDir.listFiles().orEmpty().none {
                    it.name.startsWith("personalhub-backup-") && it.name.endsWith(".db")
                },
            )
        } finally {
            second.delete()
        }
    }

    @Test fun cleanIdleStartupHasRecoveryButNoDirtyPollingThread() {
        val scheduler = CountingExportScheduler()
        HubAutoExport.setSchedulerForTests(scheduler)
        try {
            val before = Thread.getAllStackTraces().keys.count { it.name == "personalhub-dirty-check" }
            HubAutoExport.start(context)
            Thread.sleep(2500)
            val after = Thread.getAllStackTraces().keys.count { it.name == "personalhub-dirty-check" }
            assertEquals(1, scheduler.cancelledLegacy.get())
            assertEquals(1, scheduler.recovery.get())
            assertEquals(before, after)
        } finally {
            HubAutoExport.resetSchedulerForTests()
        }
    }
    private fun waitForExport(expectedGeneration: Long, label: String) {
        val deadline = System.currentTimeMillis() + 45_000
        var actual = -1L
        while (System.currentTimeMillis() < deadline) {
            try { actual = DatabaseVault.validate(context, exportCopy()) } catch (_: Exception) { }
            if (actual >= expectedGeneration) {
                Log.i("PersonalHubQA", "$label generation=$expectedGeneration exported=$actual integrity=ok")
                return
            }
            Thread.sleep(300)
        }
        fail("$label not exported; expected=$expectedGeneration actual=$actual error=${DatabaseVault.error(context)}")
    }
    @Test fun committedAddEditDeleteInEveryModuleReachSafWithoutManualExport() {
        requireEmulator()
        DatabaseVault.exportNow(context)
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val cases = listOf(
            Triple("contacts", "id", "updated_at"),
            Triple("sessions", "id", "title"),
            Triple("places", "uuid", "nickname"),
            Triple("substances", "id", "name"),
            Triple("word_entries", "id", "original_word"),
        )
        for ((table, primary, changedColumn) in cases) {
            val id = if (primary == "uuid") "'00000000-7316-4840-8000-000000000001'" else "-731684"
            val before = scalar("SELECT COUNT(*) FROM `$table`")
            val columns = db.query("PRAGMA table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
            val projection = columns.joinToString(",") { column -> when {
                column == primary -> id
                table == "contacts" && column == "public_id" -> "'qa-731684-person'"
                else -> "`$column`"
            } }
            try {
                db.beginTransaction()
                try { db.execSQL("INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) SELECT $projection FROM `$table` LIMIT 1"); db.setTransactionSuccessful() }
                finally { db.endTransaction() }
                assertEquals(before + 1, scalar("SELECT COUNT(*) FROM `$table`"))
                waitForExport(generation(), "$table add")
                db.execSQL("UPDATE `$table` SET `$changedColumn` = ? WHERE `$primary` = $id", arrayOf<Any>(if (changedColumn == "updated_at") System.currentTimeMillis() else "PersonalHub QA edited"))
                waitForExport(generation(), "$table edit")
                db.execSQL("DELETE FROM `$table` WHERE `$primary` = $id")
                waitForExport(generation(), "$table delete")
                assertEquals(before, scalar("SELECT COUNT(*) FROM `$table`"))
            } finally { db.execSQL("DELETE FROM `$table` WHERE `$primary` = $id") }
        }
        val beforeRollback = generation()
        db.beginTransaction()
        try { db.execSQL("UPDATE contacts SET updated_at=updated_at+1") } finally { db.endTransaction() }
        assertEquals("Rolled-back changes must not advance the durable generation", beforeRollback, generation())
    }
    private fun logicalHash(): String {
        val file = DatabaseVault.backupCurrent(context)
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('android_metadata','room_master_table') ORDER BY name", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
                for (table in tables) {
                    digest.update(table.toByteArray())
                    val rows = db.rawQuery("SELECT * FROM `$table`", null).use { c -> buildList {
                        while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") { index -> when(c.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                            android.database.Cursor.FIELD_TYPE_BLOB -> PhotoCapsule.sha256(c.getBlob(index))
                            else -> c.getString(index)
                        } })
                    } }
                    rows.sorted().forEach { digest.update(it.toByteArray()); digest.update(0.toByte()) }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally { file.delete() }
    }
    @Test fun settingsAndConcurrentWritesReachExport() {
        requireEmulator()
        val preferences = DatabasePreferences(context, "qa_731684")
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 20).map { key -> executor.submit { preferences.edit().putInt("key$key", key).commit() } }
            val exporting = executor.submit { DatabaseVault.exportNow(context) }
            jobs.forEach { it.get() }; exporting.get()
            assertEquals(20, preferences.all.size)
            waitForExport(generation(), "20 concurrent preference commits")
            val copy = exportCopy()
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT json FROM hub_preferences WHERE namespace='qa_731684'", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(20, org.json.JSONObject(c.getString(0)).length()) }
            }
            val before = generation()
            preferences.edit().putInt("key0", 0).apply()
            assertEquals("Unchanged preference writes must not create export loops", before, generation())
        } finally {
            executor.shutdownNow()
            PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL("DELETE FROM hub_preferences WHERE namespace='qa_731684'")
        }
        waitForExport(generation(), "preference cleanup")
    }
    @Test fun reopeningFailureRollsBackEveryTable() {
        requireEmulator()
        val before = logicalHash()
        val invalid = DatabaseVault.backupCurrent(context)
        SQLiteDatabase.openDatabase(invalid.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE room_master_table SET identity_hash='invalid-identity'") }
        val error = runCatching { DatabaseVault.importDatabase(context, Uri.fromFile(invalid)) }.exceptionOrNull()
        assertTrue(error is ImportRolledBack)
        assertEquals(before, logicalHash())
        invalid.delete()
        Log.i("PersonalHubQA", "post-replacement Room reopen failure: complete logical rollback verified")
    }
    @Test fun invalidImportsPreserveRowsAndGeneration() {
        requireEmulator()
        val before = logicalHash()
        val initialGeneration = generation()
        val people = scalar("SELECT COUNT(*) FROM contacts")
        val corrupt = File(context.cacheDir, "qa-corrupt.db").apply { writeText("not sqlite") }
        assertTrue(runCatching { DatabaseVault.importDatabase(context, Uri.fromFile(corrupt)) }.isFailure)
        assertEquals(initialGeneration, generation())
        DatabaseVault.exportNow(context)
        val incompatible = exportCopy()
        SQLiteDatabase.openDatabase(incompatible.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 999 }
        assertTrue(runCatching { DatabaseVault.importDatabase(context, Uri.fromFile(incompatible)) }.isFailure)
        assertEquals(initialGeneration, generation())
        assertEquals(people, scalar("SELECT COUNT(*) FROM contacts"))
        assertEquals(before, logicalHash())
        Log.i("PersonalHubQA", "corrupt+incompatible imports rejected; every table logically unchanged")
    }
    @Test fun repeatedImportsDeleteOrphansButProtectAPendingRollbackCopy() {
        requireEmulatorOnly()
        val databaseDir = context.getDatabasePath(PersonalHubDatabase.DB_NAME).parentFile!!
        val pending = DatabaseVault.backupCurrent(context).renameTo(File(databaseDir, "personalhub-pre-import-pending.db"))
        assertTrue(pending)
        val pendingFile = File(databaseDir, "personalhub-pre-import-pending.db")
        val orphan = DatabaseVault.backupCurrent(context).renameTo(File(databaseDir, "personalhub-pre-import-orphan.db"))
        assertTrue(orphan)
        val orphanFile = File(databaseDir, "personalhub-pre-import-orphan.db")
        val marker = File(context.filesDir, "personalhub-import.pending")
        try {
            marker.writeText(pendingFile.path)
            DatabaseVault.cleanupOrphanedPreImportBackups(context)
            assertTrue("The pending marker must protect its rollback copy", pendingFile.isFile)
            assertFalse("Unreferenced pre-import copies must be removed", orphanFile.exists())
        } finally {
            marker.delete()
            DatabaseVault.cleanupOrphanedPreImportBackups(context)
        }
        val source = DatabaseVault.backupCurrent(context)
        try {
            repeat(3) { DatabaseVault.importDatabase(context, Uri.fromFile(source)) }
            val leftovers = databaseDir.listFiles().orEmpty().filter {
                it.name.startsWith("personalhub-pre-import-") && it.name.endsWith(".db")
            }
            assertTrue("Completed imports must not accumulate rollback copies: $leftovers", leftovers.isEmpty())
        } finally {
            source.delete()
        }
    }
    @Test fun newMultiplePhotosCommitExportAndCascadeTogether() = runBlocking {
        requireEmulator()
        val owner = PersonalHubDatabase.get(context)
        val db = owner.openHelper.writableDatabase
        val photoBytes = db.query("SELECT bytes FROM people_photos LIMIT 2").use { c -> buildList { while(c.moveToNext()) add(c.getBlob(0)) } }
        val references = photoBytes.map(PhotoCapsule::stage)
        var contactId = 0L
        try {
            owner.withTransaction {
                contactId = owner.contactsDao().insertContact(com.supercontacts.app.data.local.ContactEntity(publicId = "qa-731684-photo", createdAt = 1, updatedAt = 1))
                references.forEachIndexed { index, reference ->
                    owner.contactsDao().insertField(com.supercontacts.app.data.local.ContactFieldEntity(contactId = contactId, fieldType = "photo", value = reference, addedAt = 1, position = index))
                }
                PhotoCapsule.attach(owner, contactId)
            }
            references.forEach(PhotoCapsule::discard)
            references.forEachIndexed { index, reference -> assertNull(PhotoCapsule.preview(reference)); assertArrayEquals(photoBytes[index], owner.photoDao().find(reference)!!.bytes) }
            waitForExport(generation(), "two photo BLOBs committed with one person")
            assertEquals(2L, scalar("SELECT COUNT(*) FROM people_photos WHERE contact_id=$contactId"))
            val replacement = PhotoCapsule.stage(photoBytes[1])
            owner.withTransaction {
                db.execSQL("UPDATE contact_fields SET value=? WHERE contact_id=? AND position=0", arrayOf(replacement, contactId))
                PhotoCapsule.attach(owner, contactId)
            }
            PhotoCapsule.discard(replacement)
            assertArrayEquals(photoBytes[1], owner.photoDao().find(replacement)!!.bytes)
            waitForExport(generation(), "photo BLOB replacement")
            db.execSQL("DELETE FROM contacts WHERE id=?", arrayOf(contactId))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM people_photos WHERE contact_id=$contactId"))
            waitForExport(generation(), "person deletion cascades photos")
        } finally {
            if (contactId != 0L) db.execSQL("DELETE FROM contacts WHERE id=?", arrayOf(contactId))
            references.forEach(PhotoCapsule::discard)
        }
    }
    @Test fun photoBlobsAndRelationshipsRemainValidAfterOpeningAllDaos() {
        requireEmulator()
        val owner = PersonalHubDatabase.get(context)
        owner.contactsDao(); owner.placeDao(); owner.dao(); owner.wordPulseDao()
        assertEquals(37L, scalar("SELECT COUNT(*) FROM people_photos"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM people_photos p LEFT JOIN contacts c ON p.contact_id=c.id LEFT JOIN contact_fields f ON p.field_id=f.id WHERE c.id IS NULL OR f.id IS NULL"))
        DatabaseVault.exportNow(context)
        DatabaseVault.validate(context, exportCopy())
        Log.i("PersonalHubQA", "all DAOs share one owner; photos=37 SHA256+FK verified")
    }
}
