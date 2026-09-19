package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The legacy Timer queue is still app-owned in Room; exercise its actual worker writer. */
@RunWith(AndroidJUnit4::class)
class LegacyTimerSyncPersistenceDeviceTest {
    @TableProbe("sync_meta", "sync_queue", "sync_shadow")
    @Test fun timerSyncQueueRefreshPersistsRevisionQueueAndShadow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val marker = "QA914263Sync${UUID.randomUUID()}"
        val priorRevision = db.query("SELECT long_value FROM sync_meta WHERE `key`='revision'").use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
        val queueClass = Class.forName("com.example.multitimetracker.capsules.remotesync.RemoteSyncQueueSqlite")
        val entityClass = Class.forName("com.example.multitimetracker.capsules.remotesync.RemoteEntity")
        val queue = queueClass.getConstructor(Context::class.java, String::class.java).newInstance(context, "personalhub.db")
        val payload = "{\"sync_id\":\"$marker\",\"deleted_at_ms\":null}"
        val entity = entityClass.getConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance(marker, payload, marker)
        fun count(table: String) = db.query("SELECT count(*) FROM $table WHERE sync_id=?", arrayOf(marker)).use {
            it.moveToFirst(); it.getLong(0)
        }
        try {
            queueClass.getMethod("refresh", List::class.java, Long::class.javaPrimitiveType).invoke(queue, listOf(entity), System.currentTimeMillis())
            assertEquals(1L, count("sync_queue"))
            assertEquals(1L, count("sync_shadow"))
            db.query("SELECT long_value FROM sync_meta WHERE `key`='revision'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals((priorRevision ?: 0L) + 1L, it.getLong(0))
            }
            queueClass.getMethod("refresh", List::class.java, Long::class.javaPrimitiveType).invoke(queue, listOf(entity), System.currentTimeMillis())
            assertEquals(1L, count("sync_queue"))
        } finally {
            db.execSQL("DELETE FROM sync_queue WHERE sync_id=?", arrayOf(marker))
            db.execSQL("DELETE FROM sync_shadow WHERE sync_id=?", arrayOf(marker))
            if (priorRevision == null) db.execSQL("DELETE FROM sync_meta WHERE `key`='revision'")
            else db.execSQL("UPDATE sync_meta SET long_value=? WHERE `key`='revision'", arrayOf(priorRevision))
            assertEquals(0L, count("sync_queue"))
            assertEquals(0L, count("sync_shadow"))
        }
    }
}
