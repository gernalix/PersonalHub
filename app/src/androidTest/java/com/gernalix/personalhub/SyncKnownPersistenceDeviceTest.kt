package com.gernalix.personalhub

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Upload preparation owns hub_sync_known; no network request is needed for its write contract. */
@RunWith(AndroidJUnit4::class)
class SyncKnownPersistenceDeviceTest {
    @TableProbe("hub_sync_known")
    @Test fun preparedSyncIdentityPersistsIdempotently() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val key = "QA914263Known${UUID.randomUUID()}"
        fun count() = db.query("SELECT count(*) FROM hub_sync_known WHERE table_name='quick_event_entries' AND row_key=?",
            arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }
        try {
            DatasetteSync.recordKnownIdentities(context, listOf("quick_event_entries" to key))
            assertEquals(1L, count())
            DatasetteSync.recordKnownIdentities(context, listOf("quick_event_entries" to key))
            assertEquals(1L, count())
        } finally {
            db.execSQL("DELETE FROM hub_sync_known WHERE table_name='quick_event_entries' AND row_key=?", arrayOf(key))
            assertEquals(0L, count())
        }
    }
}
