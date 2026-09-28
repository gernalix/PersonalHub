package com.gernalix.personalhub

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Legacy upload-journal tables remain part of Room schema; no runtime writer is exercised. */
@RunWith(AndroidJUnit4::class)
class LegacySyncJournalSchemaDeviceTest {
    @TableProbe("hub_sync_pending", "hub_sync_known")
    @Test fun legacySyncJournalSchemaPersistsAcrossReopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-legacy-sync-schema-${UUID.randomUUID()}.db"
        try {
            repeat(2) { pass ->
                val owner = PersonalHubDatabase.openTemporary(context, name)
                try {
                    val db = owner.openHelper.writableDatabase
                    val pendingColumns = db.query("PRAGMA table_info(hub_sync_pending)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    }
                    val knownColumns = db.query("PRAGMA table_info(hub_sync_known)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    }
                    assertEquals(setOf("table_name", "row_key", "revision"), pendingColumns)
                    assertEquals(setOf("table_name", "row_key"), knownColumns)
                    if (pass == 0) {
                        db.execSQL("INSERT INTO hub_sync_pending(table_name,row_key,revision) VALUES ('legacy','marker',7)")
                        db.execSQL("CREATE TRIGGER hub_sync_generation_INSERT AFTER INSERT ON hub_generation BEGIN SELECT 1; END")
                    } else {
                        db.query("SELECT revision FROM hub_sync_pending WHERE table_name='legacy' AND row_key='marker'").use {
                            assertEquals(true, it.moveToFirst())
                            assertEquals(7L, it.getLong(0))
                        }
                        db.query("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='hub_sync_generation_INSERT'").use {
                            it.moveToFirst()
                            assertEquals(0, it.getInt(0))
                        }
                    }
                } finally { owner.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
