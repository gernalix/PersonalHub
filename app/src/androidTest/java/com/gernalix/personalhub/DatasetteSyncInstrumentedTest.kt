package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.*
import com.gernalix.personalhub.core.database.capsules.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DatasetteSyncInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun emulator() = check(android.os.Build.MODEL.contains("sdk_gphone"))

    /** Staged through private run-as stdin; no credentials in instrumentation arguments or output. */
    @Test fun configureFromPrivateRuntimeFile() {
        val file = File(context.noBackupFilesDir, "datasette-runtime.json")
        try {
            val value = JSONObject(file.readText())
            DatasetteSync.save(context, value.getString("url"), value.getString("database"), value.getString("table"), value.getString("token"))
            DatasetteSync.setEnabled(context, false)
            assertTrue(DatasetteSettings.configuration(context).hasToken)
            assertFalse(File(context.noBackupFilesDir, "datasette.enc").readBytes().toString(Charsets.UTF_8).contains(value.getString("token")))
        } finally { file.delete() }
    }

    @Test fun localFirstFullReplicaAllTypesConcurrentMutationRetryAndDeletion() {
        emulator()
        DatasetteSync.pauseUploads {
            DatasetteSettings.setEnabled(context, false)
            val remote = mutableMapOf<String, JSONObject>()
            var calls = 0
            val sender: (DatasetteConfiguration, String, List<JSONObject>) -> Boolean = { _, _, rows ->
                calls++
                rows.forEach { row ->
                    val id = row.getString("sync_id")
                    val old = remote[id]
                    if (old == null || row.getLong("updated_at_ms") > old.getLong("updated_at_ms")) remote[id] = row
                }
                true
            }
            assertTrue(DatasetteSync.run(context, send = sender))
            assertEquals(0, calls)
            val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
            fun scalar(sql: String) = db.query(sql).use { it.moveToFirst(); it.getLong(0) }
            try {
                DatasetteSettings.setEnabled(context, true)
                assertTrue(DatasetteSync.run(context, send = sender))
                for (table in SyncJournal.tables(db)) {
                    assertEquals(table, scalar("SELECT count(*) FROM `$table`"), remote.values.count { it.getString("entity_type") == table && it.getString("payload_json") != "{}" }.toLong())
                }
                val initialIds = remote.keys.toSet()
                DatasetteSettings.setEnabled(context, true)
                assertTrue(DatasetteSync.run(context, send = sender))
                assertEquals("Initial sync must not duplicate records", initialIds, remote.keys)
                val cases = listOf(Triple("contacts", "id", "updated_at"), Triple("sessions", "id", "title"), Triple("places", "uuid", "nickname"), Triple("substances", "id", "name"), Triple("word_entries", "id", "original_word"))
                val executor = Executors.newSingleThreadExecutor()
                try { for ((table, primary, changed) in cases) {
                    val id = if (primary == "uuid") "'00000000-7316-4840-8000-000000000006'" else "-731686"
                    val columns = db.query("PRAGMA table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
                    val projection = columns.joinToString(",") { when {
                        it == primary -> id
                        table == "contacts" && it == "public_id" -> "'qa-personalhub-sync-6'"
                        else -> "`$it`"
                    } }
                    try {
                        val before = scalar("SELECT count(*) FROM `$table`")
                        db.execSQL("INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) SELECT $projection FROM `$table` LIMIT 1")
                        assertEquals(before + 1, scalar("SELECT count(*) FROM `$table`"))
                        assertFalse("Server/network failure must keep the journal", DatasetteSync.run(context, send = { _, _, _ -> false }))
                        assertTrue(DatasetteSync.pending(context) > 0)
                        assertEquals(before + 1, scalar("SELECT count(*) FROM `$table`"))
                        var injected = false
                        assertTrue(DatasetteSync.run(context, send = { config, token, rows ->
                            if (!injected) {
                                injected = true
                                // A committed edit while an older upload is in flight must survive its ACK.
                                executor.submit { db.execSQL("UPDATE `$table` SET `$changed`=? WHERE `$primary`=$id", arrayOf<Any>(if (changed == "updated_at") 987654321L else "QA newer state")) }.get()
                            }
                            sender(config, token, rows)
                        }))
                        val added = remote.values.single { row ->
                            val payload = JSONObject(row.getString("payload_json"))
                            row.getString("entity_type") == table && payload.optString(primary) == id.trim('\'')
                        }
                        assertEquals(if (changed == "updated_at") "987654321" else "QA newer state", JSONObject(added.getString("payload_json")).getString(changed))
                        if (table == "contacts") {
                            db.execSQL("UPDATE contacts SET deleted_at=1234 WHERE id=$id")
                            assertTrue(DatasetteSync.run(context, send = sender))
                            assertEquals(1234L, remote.getValue(added.getString("sync_id")).getLong("deleted_at_ms"))
                            db.execSQL("UPDATE contacts SET deleted_at=NULL WHERE id=$id")
                            assertTrue(DatasetteSync.run(context, send = sender))
                            assertTrue(remote.getValue(added.getString("sync_id")).isNull("deleted_at_ms"))
                        }
                        db.execSQL("DELETE FROM `$table` WHERE `$primary`=$id")
                        assertTrue(DatasetteSync.run(context, send = sender))
                        assertFalse(remote.getValue(added.getString("sync_id")).isNull("deleted_at_ms"))
                        assertEquals(before, scalar("SELECT count(*) FROM `$table`"))
                    } finally { db.execSQL("DELETE FROM `$table` WHERE `$primary`=$id") }
                } } finally { executor.shutdownNow() }
                DatasetteSettings.setEnabled(context, false)
                val beforeOff = calls
                DatabasePreferences(context, "datasette_qa").edit().putString("offline", "saved").commit()
                assertTrue(DatasetteSync.run(context, send = sender))
                assertEquals(beforeOff, calls)
                db.execSQL("DELETE FROM hub_preferences WHERE namespace='datasette_qa'")
            } finally { DatasetteSettings.setEnabled(context, false) }
        }
    }

    @Test fun largePhotoEncodingAllowsConcurrentWriterAndRetainsNewRevision() {
        emulator()
        DatasetteSync.pauseUploads {
            DatasetteSettings.setEnabled(context, true)
            val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
            val owner = PersonalHubDatabase.get(context)
            val reference = db.query("SELECT reference FROM people_photos LIMIT 1").use { assertTrue(it.moveToFirst()); it.getString(0) }
            val original = requireNotNull(owner.photoDao().find(reference))
            val large = ByteArray(1024 * 1024) { (it % 251).toByte() }
            val digest = MessageDigest.getInstance("SHA-256").digest(large).joinToString("") { "%02x".format(it) }
            val executor = Executors.newSingleThreadExecutor()
            val sent = mutableListOf<JSONObject>()
            var edited = false
            try {
                assertTrue(DatasetteSync.run(context, send = { _, _, _ -> true }))
                owner.photoDao().put(original.copy(bytes = large, sha256 = digest))
                assertTrue(DatasetteSync.run(context, send = { _, _, rows -> sent.addAll(rows.filter { it.getString("entity_type") == "people_photos" }); true }, encodeBlob = { bytes ->
                    if (!edited) {
                        edited = true
                        // This executes inside the encoder, after capture and before JSON/HTTP.
                        // If the gate is held during encoding, the writer times out and this test fails.
                        executor.submit {
                            db.execSQL("UPDATE people_photos SET created_at=created_at+1 WHERE field_id=?", arrayOf(original.fieldId))
                        }.get(2, TimeUnit.SECONDS)
                    }
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }))
                assertTrue(edited)
                assertEquals(2, sent.size)
                val old = JSONObject(sent.first().getString("payload_json"))
                val latest = JSONObject(sent.last().getString("payload_json"))
                assertEquals(original.createdAt, old.getLong("created_at"))
                assertEquals(original.createdAt + 1, latest.getLong("created_at"))
                assertArrayEquals(large, android.util.Base64.decode(latest.getJSONObject("bytes").getString("data"), android.util.Base64.NO_WRAP))
                assertEquals(0L, DatasetteSync.pending(context))
            } finally {
                owner.photoDao().put(original)
                DatasetteSettings.setEnabled(context, false)
                executor.shutdownNow()
            }
        }
    }

    /** Run followed by importReconciliationAfterProcessRestart in a fresh instrument process. */
    @Test fun importPreservesKnownIdentitiesForReconciliation() {
        emulator()
        DatasetteSync.pauseUploads {
            DatasetteSettings.setEnabled(context, false)
            val before = DatabaseVault.backupCurrent(context)
            try {
                DatabasePreferences(context, "qa_import_sync_7").edit().putInt("value", 7).commit()
                DatasetteSettings.setEnabled(context, true)
                var uploaded: String? = null
                assertTrue(DatasetteSync.run(context, send = { _, _, rows ->
                    rows.firstOrNull { it.getString("entity_type") == "hub_preferences" && JSONObject(it.getString("payload_json")).optString("namespace") == "qa_import_sync_7" }?.let { uploaded = it.getString("sync_id") }
                    true
                }))
                File(context.noBackupFilesDir, "qa-import-sync-id").writeText(requireNotNull(uploaded))
                DatasetteSettings.setEnabled(context, false)
                DatabaseVault.importDatabase(context, android.net.Uri.fromFile(before))
                // Import freezes the old object graph until app restart. Inspect the committed file.
                android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(PersonalHubDatabase.DB_NAME).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT count(*) FROM hub_preferences WHERE namespace='qa_import_sync_7'", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                    db.rawQuery("SELECT count(*) FROM hub_sync_known WHERE table_name='hub_preferences'", null).use { assertTrue(it.moveToFirst()); assertTrue(it.getInt(0)>0) }
                }
            } finally { before.delete() }
        }
    }

    @Test fun importReconciliationAfterProcessRestart() {
        emulator()
        DatasetteSync.pauseUploads {
            val marker = File(context.noBackupFilesDir, "qa-import-sync-id")
            val expected = marker.readText()
            try {
                DatasetteSettings.setEnabled(context, true)
                var tombstone = false
                assertTrue(DatasetteSync.run(context, send = { _, _, rows ->
                    rows.firstOrNull { it.getString("sync_id") == expected }?.let { tombstone = !it.isNull("deleted_at_ms") && it.getString("payload_json") == "{}" }
                    true
                }))
                assertTrue("Removed imported identities must emit tombstones after restart", tombstone)
                assertEquals(0L, DatasetteSync.pending(context))
            } finally { DatasetteSettings.setEnabled(context, false); marker.delete() }
        }
    }

    @Test fun oracleFullRoundTripUpload() {
        DatasetteSync.pauseUploads {
            DatasetteSettings.setEnabled(context, true)
            var complete = false
            repeat(5) {
                if (!complete) complete = DatasetteSync.run(context)
            }
            assertTrue("Oracle upload failed; queued changes retained", complete)
            assertEquals(0L, DatasetteSync.pending(context))
        }
    }
}
