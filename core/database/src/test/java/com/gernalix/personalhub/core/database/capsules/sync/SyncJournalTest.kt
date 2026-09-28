package com.gernalix.personalhub.core.database.capsules.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataTracking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncJournalTest {

    @Test fun compositePrimaryKeyEncodingRoundTripsTypedValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = PersonalHubDatabase.openTemporary(context, "sync-key-helper-test.db")
        try {
            val db = owner.openHelper.writableDatabase
            db.execSQL("CREATE TABLE sync_key_fixture (text_key TEXT NOT NULL, number_key INTEGER NOT NULL, PRIMARY KEY(text_key, number_key))")
            db.execSQL("INSERT INTO sync_key_fixture VALUES ('a:b''c', 42)")
            val keys = SyncJournal.primaryKeys(db, "sync_key_fixture")
            assertEquals(listOf("text_key", "number_key"), keys)
            val encoded = db.query("SELECT ${SyncJournal.keyExpression(keys)} FROM sync_key_fixture").use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            }
            assertArrayEquals(arrayOf("a:b'c", 42L), SyncJournal.keyValues(encoded))
        } finally { owner.close(); context.deleteDatabase("sync-key-helper-test.db") }
    }
    @Test fun gitTrackingIgnoresNoOpUpdatesButRecordsRealUpdates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = PersonalHubDatabase.openTemporary(context, "git-noop-update-test.db")
        try {
            val db = owner.openHelper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=OFF")
            GitDataTracking.install(db, enqueueAll = false)
            db.execSQL(
                "INSERT INTO place_events(id,event_uuid,session_uuid,place_id,event_type,timestamp,lat,lon,accuracy_m,source,notes) " +
                    "VALUES (1,'event-noop','event-noop','place-1','CHECK_IN',1000,NULL,NULL,NULL,'test',NULL)",
            )
            db.execSQL("DELETE FROM hub_git_events")
            db.execSQL("DELETE FROM hub_git_pending")

            db.execSQL("UPDATE place_events SET timestamp=timestamp WHERE id=1")
            db.query("SELECT count(*) FROM hub_git_events").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.query("SELECT count(*) FROM hub_git_pending").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }

            db.execSQL("UPDATE place_events SET timestamp=1001 WHERE id=1")
            db.query("SELECT count(*) FROM hub_git_events WHERE table_name='place_events' AND operation='UPDATE' AND before_payload IS NOT after_payload").use {
                it.moveToFirst(); assertEquals(1, it.getInt(0))
            }
            db.query("SELECT count(*) FROM hub_git_pending WHERE table_name='place_events'").use {
                it.moveToFirst(); assertEquals(1, it.getInt(0))
            }
        } finally { owner.close(); context.deleteDatabase("git-noop-update-test.db") }
    }

    @Test fun gitTrackingAllowsRoomReplaceWhenTableIsAlreadyPending() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = PersonalHubDatabase.openTemporary(context, "git-replace-pending-test.db")
        try {
            val db = owner.openHelper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=OFF")
            GitDataTracking.install(db, enqueueAll = false)
            val row = "place_events(id,event_uuid,session_uuid,place_id,event_type,timestamp,lat,lon,accuracy_m,source,notes) " +
                "VALUES (1,'event-replace','event-replace','place-1','CHECK_IN',1000,NULL,NULL,NULL,'test',NULL)"
            db.execSQL("INSERT INTO $row")
            db.execSQL("INSERT OR REPLACE INTO $row")
            db.query("SELECT revision FROM hub_git_pending WHERE table_name='place_events'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getLong(0) >= 2L)
            }
        } finally { owner.close(); context.deleteDatabase("git-replace-pending-test.db") }
    }

}
