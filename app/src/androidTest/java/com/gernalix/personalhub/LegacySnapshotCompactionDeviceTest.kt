package com.gernalix.personalhub

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.multitimetracker.persistence.SnapshotSqlite
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A legacy JSON row is a fixture; the production compactor writes the durable checkpoint. */
@RunWith(AndroidJUnit4::class)
class LegacySnapshotCompactionDeviceTest {
    @TableProbe("snapshot_history", "snapshot_payloads")
    @Test fun legacySnapshotCompactionPersistsHistoryAndPayload() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val marker = "QA914263Snapshot${UUID.randomUUID()}"
        val json = "{\"marker\":\"$marker\"}"
        assertEquals(0L, db.query("SELECT count(*) FROM snapshot_history WHERE json IS NOT NULL AND length(json)>0").use {
            it.moveToFirst(); it.getLong(0)
        })
        var historyId: Long? = null
        var payloadId: Long? = null
        try {
            db.execSQL("INSERT INTO snapshot_history(json,saved_at_ms,kind) VALUES (?,?,'legacy_snapshot')",
                arrayOf(json, System.currentTimeMillis()))
            historyId = db.query("SELECT id FROM snapshot_history WHERE json=?", arrayOf(json)).use {
                assertTrue(it.moveToFirst()); it.getLong(0)
            }
            assertTrue(SnapshotSqlite.compactSnapshotHistoryStorage(context))
            payloadId = db.query("SELECT payload_id FROM snapshot_history WHERE id=? AND kind='checkpoint' AND json=''",
                arrayOf(historyId!!)).use { assertTrue(it.moveToFirst()); it.getLong(0) }
            db.query("SELECT json,size_bytes FROM snapshot_payloads WHERE id=?", arrayOf(payloadId!!)).use {
                assertTrue(it.moveToFirst())
                assertEquals(json, it.getString(0))
                assertEquals(json.toByteArray(Charsets.UTF_8).size.toLong(), it.getLong(1))
            }
        } finally {
            historyId?.let { db.execSQL("DELETE FROM snapshot_history WHERE id=?", arrayOf(it)) }
            payloadId?.let { db.execSQL("DELETE FROM snapshot_payloads WHERE id=?", arrayOf(it)) }
            assertEquals(0L, db.query("SELECT count(*) FROM snapshot_payloads WHERE json=?", arrayOf(json)).use {
                it.moveToFirst(); it.getLong(0)
            })
        }
    }
}
