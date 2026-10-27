package com.gernalix.personalhub

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.HubAutoExport
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic ONLY. Requires a separate, ephemeral .qa package on an emulator.
 * Creates synthetic photo-like BLOBs; never reads or modifies a real user DB.
 */
@RunWith(AndroidJUnit4::class)
class AutoExportRuntimeCostQaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tag = "PHRuntimeAudit"

    private fun guard() {
        check(context.packageName == "com.gernalix.personalhub.qa") { "QA package only" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish") &&
            Build.MODEL.contains("sdk", ignoreCase = true)) { "Emulator only" }
    }

    private class CountingScheduler : HubAutoExport.Scheduler {
        val enqueues = AtomicInteger(0)
        override fun cancelLegacyWork(context: Context) = Unit
        override fun enqueuePeriodicRecovery(context: Context) = Unit
        override fun enqueueAutoExport(context: Context, policy: ExistingWorkPolicy) {
            check(policy == ExistingWorkPolicy.REPLACE)
            enqueues.incrementAndGet()
        }
    }

    private class LocalExportPublisher(private val root: File) : DatabaseVault.ExportPublisher {
        private inner class Entry(private val file: File) : DatabaseVault.ExportFile {
            override val identity: String get() = file.absolutePath
            override val name: String get() = file.name
            override fun delete(): Boolean = file.delete()
        }

        private fun child(name: String): File {
            require(name.matches(Regex("[a-zA-Z0-9_.-]+"))) { "Unsafe synthetic export name" }
            return File(root, name)
        }

        override fun create(name: String): DatabaseVault.ExportFile {
            val file = child(name)
            check(file.createNewFile()) { "Staged export already exists: " + name }
            return Entry(file)
        }

        override fun open(identity: String): DatabaseVault.ExportFile? {
            val path = File(identity)
            if (path.parentFile?.canonicalFile != root.canonicalFile || !path.isFile) return null
            return Entry(path)
        }

        override fun find(name: String): DatabaseVault.ExportFile? =
            child(name).takeIf(File::isFile)?.let(::Entry)

        override fun list(): List<DatabaseVault.ExportFile> =
            root.listFiles().orEmpty().filter(File::isFile).map(::Entry)

        override fun rename(source: DatabaseVault.ExportFile, newName: String): DatabaseVault.ExportFile {
            val old = File(source.identity)
            val next = child(newName)
            check(old.renameTo(next)) { "Cannot rename synthetic export" }
            return Entry(next)
        }

        override fun writeFrom(source: File, target: DatabaseVault.ExportFile) {
            source.copyTo(File(target.identity), overwrite = true)
        }

        override fun readTo(source: DatabaseVault.ExportFile, target: File) {
            File(source.identity).copyTo(target, overwrite = true)
        }

        fun canonicalBytes(): Long = child(PersonalHubDatabase.DB_NAME).length()
    }

    private fun metric(json: JSONObject) {
        Log.i(tag, "PH_EXPORT_METRIC:" + json.toString())
    }

    @Test
    fun profileRealDatabaseVaultExportWithSyntheticPhotoAndRapidWrites() {
        guard() // fail closed BEFORE any mutation
        val rootPrefs = context.getSharedPreferences("personalhub_transfer", Context.MODE_PRIVATE)
        val profilePrefs = context.getSharedPreferences("personalhub_transfer", Context.MODE_PRIVATE)
        val previousTree = rootPrefs.getString("tree_uri", null)
        val previousIdentity = profilePrefs.getString(DatabaseVault.CANONICAL_DOCUMENT_URI, null)
        val previousGeneration = profilePrefs.getLong("exported_generation", -1L)
        val directory = File(context.cacheDir, "runtime-export-only").apply {
            if (exists()) deleteRecursively()
            check(mkdirs())
        }
        val publisher = LocalExportPublisher(directory)
        val scheduler = CountingScheduler()
        val mebibyte = 1024 * 1024
        val fixture = -91527
        try {
            HubAutoExport.setSchedulerForTests(scheduler)
            DatabaseVault.setExportPublisherFactoryForTests { _, _ -> publisher }
            check(rootPrefs.edit().putString("tree_uri", "content://qa-runtime-only/synthetic")
                .commit())
            check(profilePrefs.edit().remove(DatabaseVault.CANONICAL_DOCUMENT_URI)
                .putLong("exported_generation", -1L).commit())

            val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO contacts(id,public_id,created_at,updated_at) VALUES(?, ?, 1, 1)",
                arrayOf(fixture, "audit-synthetic-contact"),
            )
            db.execSQL(
                "INSERT INTO contact_fields(id,contact_id,field_type,value,added_at,position,is_primary) " +
                    "VALUES(?,?, 'photo','synthetic',1,0,0)",
                arrayOf(fixture, fixture),
            )
            db.execSQL(
                "INSERT INTO people_photos(field_id,contact_id,reference,mime_type,bytes,sha256,created_at) " +
                    "VALUES(?,?, 'audit-fixture','application/octet-stream',zeroblob(?),'synthetic',1)",
                arrayOf(fixture, fixture, 2 * mebibyte),
            )

            // Importantly, this calls actual DatabaseVault.exportNow including SQLite snapshot,
            // schema/integrity validation, staged write, two readback validations and hashes.
            for (payload in listOf(2, 16, 48)) {
                db.execSQL(
                    "UPDATE people_photos SET bytes=zeroblob(?) WHERE field_id=?",
                    arrayOf(payload * mebibyte, fixture),
                )
                val started = SystemClock.elapsedRealtimeNanos()
                assertTrue(DatabaseVault.exportNow(context))
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                assertEquals(DatabaseVault.currentGeneration(context), DatabaseVault.exportedGeneration(context))
                val bytes = publisher.canonicalBytes()
                assertTrue("export unexpectedly too small", bytes > payload.toLong() * mebibyte)
                metric(JSONObject().put("case", "export")
                    .put("payload_mib", payload)
                    .put("exported_bytes", bytes)
                    .put("elapsed_ms", elapsedMs)
                    .put("queued_before", scheduler.enqueues.get()))
            }

            // Simulate a burst of user edits while the debounce coalesces export work.
            val queuedBefore = scheduler.enqueues.get()
            val mutationStarted = SystemClock.elapsedRealtimeNanos()
            repeat(25) { index ->
                db.execSQL("UPDATE contacts SET updated_at=? WHERE id=?",
                    arrayOf(index + 10L, fixture))
            }
            val mutationMs = (SystemClock.elapsedRealtimeNanos() - mutationStarted) / 1_000_000.0
            val deltaRequests = scheduler.enqueues.get() - queuedBefore
            assertTrue("committed writes did not schedule export", deltaRequests >= 25)
            val exportStarted = SystemClock.elapsedRealtimeNanos()
            assertTrue(HubAutoExport.exportUntilClean(context) { false })
            val exportMs = (SystemClock.elapsedRealtimeNanos() - exportStarted) / 1_000_000.0
            assertEquals(DatabaseVault.currentGeneration(context), DatabaseVault.exportedGeneration(context))
            metric(JSONObject().put("case", "burst")
                .put("mutations", 25)
                .put("scheduled_work_replacements", deltaRequests)
                .put("mutation_elapsed_ms", mutationMs)
                .put("post_burst_export_ms", exportMs)
                .put("exported_bytes", publisher.canonicalBytes())
                .put("final_generation", DatabaseVault.exportedGeneration(context)))
        } finally {
            HubAutoExport.resetSchedulerForTests()
            DatabaseVault.setExportPublisherFactoryForTests(null)
            rootPrefs.edit().apply {
                if (previousTree == null) remove("tree_uri") else putString("tree_uri", previousTree)
            }.commit()
            profilePrefs.edit().apply {
                if (previousIdentity == null) remove(DatabaseVault.CANONICAL_DOCUMENT_URI)
                else putString(DatabaseVault.CANONICAL_DOCUMENT_URI, previousIdentity)
                putLong("exported_generation", previousGeneration)
            }.commit()
            directory.deleteRecursively()
            // The synthetic 48 MiB DB fixture intentionally remains in disposable .qa
            // for subsequent startup timing in this *same ephemeral emulator run*.
        }
    }
}
