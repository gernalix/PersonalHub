package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportedTriggerValidationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val target get() = context.getDatabasePath(PersonalHubDatabase.DB_NAME)

    @Before fun createSyntheticCurrentDatabase() {
        DatabaseGate.resume()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        DatabaseVault.preferences(context).edit().clear().commit()
        DatabaseVault.setDirectorySyncForTests { }
        PersonalHubDatabase.openTemporary(context, PersonalHubDatabase.DB_NAME).let { owner ->
            try { owner.openHelper.writableDatabase } finally { owner.close() }
        }
    }

    @After fun closeSyntheticStorage() {
        // A successful import intentionally freezes app writers until restart.
        // Each synthetic case must restore that process-local state for the next one.
        DatabaseGate.resume()
        DatabaseVault.setDirectorySyncForTests(null)
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        DatabaseVault.preferences(context).edit().clear().commit()
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Untrusted trigger must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("trigger", ignoreCase = true))
        }
    }

    @Test fun arbitraryActivityTriggerIsRejectedWithoutReplacingCurrentData() {
        val incoming = File(context.cacheDir, "synthetic-incoming.db")
        target.copyTo(incoming, overwrite = true)
        SQLiteDatabase.openDatabase(incoming.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER hub_activity_payload AFTER INSERT ON finance_transactions BEGIN UPDATE finance_transactions SET amount='0' WHERE uuid=NEW.uuid; END")
        }
        val before = target.readBytes()
        assertRejected { DatabaseVault.validate(context, incoming) }
        assertRejected { DatabaseVault.importDatabase(context, Uri.fromFile(incoming)) }
        assertArrayEquals(before, target.readBytes())
    }

    @Test fun alteredCanonicalBodyIsRejected() {
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER IF EXISTS hub_activity_finance_transactions_INSERT")
            db.execSQL("CREATE TRIGGER `hub_activity_finance_transactions_INSERT` AFTER INSERT ON `finance_transactions` BEGIN UPDATE finance_transactions SET amount='0' WHERE uuid=NEW.uuid; END")
        }
        assertRejected { DatabaseVault.validate(context, target) }
    }

    @Test fun exactInstalledBodiesAllowHistoricalAppVersionsAndAbsentCapture() {
        for (version in listOf(0L, 1L, Long.MAX_VALUE)) {
            PersonalHubDatabase.openTemporary(context, PersonalHubDatabase.DB_NAME).let { owner ->
                try { HubActivityCapture.install(owner.openHelper.writableDatabase, version) }
                finally { owner.close() }
            }
            assertTrue(DatabaseVault.validate(context, target) >= 0L)
        }
        PersonalHubDatabase.openTemporary(context, PersonalHubDatabase.DB_NAME).let { owner ->
            try { HubActivityCapture.uninstall(owner.openHelper.writableDatabase) }
            finally { owner.close() }
        }
        assertTrue(DatabaseVault.validate(context, target) >= 0L)
    }

    @Test fun completeRemovalEscapesNamesAndDoesNotMatchOtherPrefixes() {
        PersonalHubDatabase.openTemporary(context, PersonalHubDatabase.DB_NAME).let { owner ->
            try {
                val db = owner.openHelper.writableDatabase
                db.execSQL("CREATE TRIGGER `hub_activity_payload``quoted` AFTER INSERT ON finance_transactions BEGIN SELECT 1; END")
                db.execSQL("CREATE TRIGGER hubxactivity_unrelated AFTER INSERT ON finance_transactions BEGIN SELECT 1; END")
                HubActivityCapture.uninstall(db)
                val names = db.query("SELECT name FROM sqlite_master WHERE type='trigger'").use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
                assertFalse(names.any { it.startsWith("hub_activity_") })
                assertTrue("Unrelated prefix preserved", "hubxactivity_unrelated" in names)
            } finally { owner.close() }
        }
    }

    private fun schema25(file: File) {
        val schema = JSONObject(context.assets.open(
            "com.gernalix.personalhub.core.database.PersonalHubDatabase/25.json",
        ).bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indexes.length()) {
                    db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
                }
            }
            db.version = 25
        }
    }

    @Test fun supportedMigrationChecksActivityAndOtherTriggerFamiliesBeforeOpeningRoom() {
        for ((name, body) in listOf(
            "hub_activity_payload" to "UPDATE finance_transactions SET amount='0';",
            "hub_dirty_finance_transactions_INSERT" to "DELETE FROM finance_transactions;",
        )) {
            val incoming = File(context.cacheDir, "synthetic-$name.db")
            schema25(incoming)
            SQLiteDatabase.openDatabase(incoming.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER `$name` AFTER INSERT ON finance_transactions BEGIN $body END")
            }
            val before = target.readBytes()
            assertRejected { DatabaseVault.importDatabase(context, Uri.fromFile(incoming)) }
            assertArrayEquals(before, target.readBytes())
            assertEquals(25, SQLiteDatabase.openDatabase(incoming.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version })
        }
    }

    @Test fun supportedRaw25ImportPreservesDataAndAcceptsHistoricalActivitySql() {
        val incoming = File(context.cacheDir, "synthetic-compatible25.db")
        schema25(incoming)
        SQLiteDatabase.openDatabase(incoming.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO places(uuid,nickname,address,lat,lon,created_at,updated_at,archived) VALUES('synthetic-place','Synthetic home','Synthetic address',55.6,12.6,1000,1000,0)")
            db.execSQL("INSERT INTO hub_entities(canonical_id,entity_kind,owning_module,local_table,local_key,lifecycle,created_at,updated_at) VALUES('synthetic-place','places/place','places','places','synthetic-place','ACTIVE',1000,1000)")
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            val columns = tables.associateWith { table -> db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            } }
            HubActivityCapture.expectedSql(columns, 7L).values.forEach(db::execSQL)
        }
        DatabaseVault.importDatabase(context, Uri.fromFile(incoming))
        assertTrue(DatabaseVault.validate(context, target) >= 0L)
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(26, db.version)
            db.rawQuery("SELECT address FROM places WHERE uuid='synthetic-place'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Synthetic address", c.getString(0))
            }
        }
        assertEquals(25, SQLiteDatabase.openDatabase(incoming.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version })
    }

    @Test fun onlyTheAppVersionLiteralCanVaryInGeneratedSql() {
        val columns = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            names.associateWith { table -> db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            } }
        }
        val templates = HubActivityCapture.expectedSql(columns)
        for ((name, sql) in HubActivityCapture.expectedSql(columns, 7L)) {
            assertTrue(name, HubActivityCapture.acceptsTrigger(sql, templates[name]))
            assertFalse(name, HubActivityCapture.acceptsTrigger(sql + " SELECT 1;", templates[name]))
            assertFalse(name, HubActivityCapture.acceptsTrigger(sql.replace("hub_activity_log", "hub_generation"), templates[name]) && sql.contains("hub_activity_log"))
        }
        assertFalse(HubActivityCapture.acceptsTrigger("CREATE TRIGGER hub_activity_payload", null))
    }
}
