package com.gernalix.personalhub

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Immutable legacy stores retained by Room; no runtime writer remains for either table. */
@RunWith(AndroidJUnit4::class)
class LegacyBackupMetadataSchemaDeviceTest {
    @TableProbe("backup_metadata", "audit_events")
    @Test fun legacyBackupMetadataSchemaPersistsAcrossReopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-backup-metadata-${UUID.randomUUID()}.db"
        try {
            repeat(2) {
                val owner = PersonalHubDatabase.openTemporary(context, name)
                try {
                    val db = owner.openHelper.readableDatabase
                    assertEquals(17, db.version)
                    val columns = db.query("PRAGMA table_info(backup_metadata)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    }
                    assertEquals(setOf("id", "app_id", "schema_version", "backup_format_version", "exported_at"), columns)
                    val auditColumns = db.query("PRAGMA table_info(audit_events)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    }
                    assertEquals(setOf("id", "ts_ms", "is_system", "action", "entity_type", "entity_id",
                        "summary", "payload_json", "undone_at_ms"), auditColumns)
                } finally { owner.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
