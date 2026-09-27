package com.gernalix.personalhub

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Backup metadata remains; legacy Timer audit_events must never return. */
@RunWith(AndroidJUnit4::class)
class LegacyBackupMetadataSchemaDeviceTest {
    @TableProbe("backup_metadata", "alert_firings")
    @Test fun legacyBackupMetadataSchemaPersistsAcrossReopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-backup-metadata-${UUID.randomUUID()}.db"
        try {
            repeat(2) {
                val owner = PersonalHubDatabase.openTemporary(context, name)
                try {
                    val db = owner.openHelper.readableDatabase
                    assertEquals(PersonalHubDatabase.SCHEMA_VERSION, db.version)
                    val columns = db.query("PRAGMA table_info(backup_metadata)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    }
                    assertEquals(setOf("id", "app_id", "schema_version", "backup_format_version", "exported_at"), columns)
                    val legacyAuditExists = db.query(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='audit_events' LIMIT 1",
                    ).use { it.moveToFirst() }
                    assertEquals(false, legacyAuditExists)
                    val firingColumns = db.query("PRAGMA table_info(alert_firings)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    }
                    assertEquals(setOf("id", "rule_id", "domain", "trigger", "entity_id", "entity_label",
                        "tag_names", "delivery", "message", "fired_at"), firingColumns)
                } finally { owner.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
