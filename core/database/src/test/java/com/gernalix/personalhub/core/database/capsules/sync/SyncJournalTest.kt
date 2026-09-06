package com.gernalix.personalhub.core.database.capsules.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
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
    @Test fun versionTwoWithLegacyTimerIndicesMigratesWithoutChangingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("sync-upgrade-test.db")
        file.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO hub_generation VALUES (1,42)")
            old.execSQL("INSERT INTO sessions VALUES (1,'preserved',1000,2000,NULL,1000,2000,NULL)")
            old.execSQL("CREATE INDEX idx_sessions_updated ON sessions(updated_at_ms DESC,id DESC)")
            old.version = 2
        }
        val owner = PersonalHubDatabase.openTemporary(context, file.absolutePath)
        try {
            val db = owner.openHelper.writableDatabase
            assertEquals(PersonalHubDatabase.SCHEMA_VERSION, db.version)
            db.query("SELECT title,start_ms,end_ms FROM sessions").use { assertTrue(it.moveToFirst()); assertEquals("preserved", it.getString(0)); assertEquals(1000L, it.getLong(1)); assertEquals(2000L, it.getLong(2)) }
            db.query("SELECT generation FROM hub_generation").use { it.moveToFirst(); assertEquals(44L, it.getLong(0)) }
            db.execSQL("UPDATE sessions SET title='changed'")
            db.query("SELECT count(*) FROM hub_sync_pending WHERE table_name='sessions'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        } finally { owner.close(); context.deleteDatabase("sync-upgrade-test.db") }
    }

    @Test fun everySchemaTypeJournalsCreateUpdateDeleteAndRollbackAtomically() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = PersonalHubDatabase.openTemporary(context, "sync-journal-test.db")
        try {
            val db = owner.openHelper.writableDatabase
            // Fixture values need not form a domain graph. This isolated DB is never uploaded.
            db.execSQL("PRAGMA foreign_keys=OFF")
            val tables = SyncJournal.tables(db)
            assertEquals(59, tables.size)
            for (table in tables) {
                db.execSQL("DELETE FROM `$table`")
                db.execSQL("DELETE FROM hub_sync_pending")
                val columns = db.query("PRAGMA table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1) to c.getString(2)) } }
                val names = columns.joinToString(",") { "`${it.first}`" }
                val values = columns.joinToString(",") { when (it.second) { "INTEGER" -> "1"; "REAL" -> "1.5"; "BLOB" -> "X'0123AB'"; else -> "'fixture:with''quotes'" } }
                db.execSQL("INSERT INTO `$table` ($names) VALUES ($values)")
                val key = db.query("SELECT row_key FROM hub_sync_pending WHERE table_name=?", arrayOf(table)).use { assertTrue(table, it.moveToFirst()); it.getString(0) }
                val where = SyncJournal.primaryKeys(db, table).joinToString(" AND ") { "`$it` IS ?" }
                db.query("SELECT count(*) FROM `$table` WHERE $where", SyncJournal.keyValues(key)).use { it.moveToFirst(); assertEquals(table, 1, it.getInt(0)) }
                val before = db.query("SELECT revision FROM hub_sync_pending WHERE table_name=?", arrayOf(table)).use { it.moveToFirst(); it.getLong(0) }
                db.execSQL("UPDATE `$table` SET `${columns.first().first}`=`${columns.first().first}`")
                val after = db.query("SELECT revision FROM hub_sync_pending WHERE table_name=?", arrayOf(table)).use { it.moveToFirst(); it.getLong(0) }
                assertTrue(table, after > before)
                db.beginTransaction()
                try { db.execSQL("DELETE FROM `$table`") } finally { db.endTransaction() }
                assertEquals(after, db.query("SELECT revision FROM hub_sync_pending WHERE table_name=?", arrayOf(table)).use { it.moveToFirst(); it.getLong(0) })
                db.execSQL("DELETE FROM `$table`")
                db.query("SELECT row_key,revision FROM hub_sync_pending WHERE table_name=?", arrayOf(table)).use { assertTrue(it.moveToFirst()); assertEquals(key, it.getString(0)); assertTrue(it.getLong(1) > after) }
            }
        } finally { owner.close(); context.deleteDatabase("sync-journal-test.db") }
    }

    @Test fun changedCompositeKeyRetainsOldIdentityAndQueueWritesDoNotDirtyExports() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = PersonalHubDatabase.openTemporary(context, "sync-key-test.db")
        try {
            val db = owner.openHelper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.execSQL("INSERT INTO contact_tags(contact_id,tag_id,added_at) VALUES ('a:b',1,1)")
            db.execSQL("UPDATE contact_tags SET tag_id=2")
            db.query("SELECT count(*) FROM hub_sync_pending WHERE table_name='contact_tags'").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            val generation = db.query("SELECT generation FROM hub_generation").use { it.moveToFirst(); it.getLong(0) }
            db.execSQL("DELETE FROM hub_sync_pending")
            db.execSQL("INSERT INTO hub_sync_known VALUES ('contact_tags','known')")
            assertEquals(generation, db.query("SELECT generation FROM hub_generation").use { it.moveToFirst(); it.getLong(0) })
        } finally { owner.close(); context.deleteDatabase("sync-key-test.db") }
    }

    @Test fun installingJournalReplacesNonIdempotentTriggersAndCoalescesRepeatedUpdates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = PersonalHubDatabase.openTemporary(context, "sync-trigger-replace-test.db")
        try {
            val db = owner.openHelper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.execSQL("DROP TRIGGER IF EXISTS `hub_sync_place_events_UPDATE`")
            db.execSQL(
                "CREATE TRIGGER `hub_sync_place_events_UPDATE` AFTER UPDATE ON `place_events` BEGIN " +
                    "INSERT INTO hub_sync_pending(table_name,row_key,revision) SELECT 'place_events',hex(quote(NEW.`id`)),1; END",
            )
            SyncJournal.install(db)

            db.execSQL(
                "INSERT INTO place_events(id,event_uuid,session_uuid,place_id,event_type,timestamp,lat,lon,accuracy_m,source,notes) " +
                    "VALUES (1,'event-1','event-1','place-1','CHECK_IN',1000,NULL,NULL,NULL,'test',NULL)",
            )
            db.execSQL("UPDATE place_events SET timestamp=1001 WHERE id=1")
            db.execSQL("UPDATE place_events SET timestamp=1002 WHERE id=1")

            db.query("SELECT count(*), max(revision) FROM hub_sync_pending WHERE table_name='place_events' AND row_key=hex(quote(1))").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertTrue(it.getLong(1) >= 3)
            }
        } finally { owner.close(); context.deleteDatabase("sync-trigger-replace-test.db") }
    }
}
