package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubContextMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun versionSixMigratesWithoutChangingExistingDataAndEnforcesGraphForeignKeys() {
        val name = "hub-context-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val entities = JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/6.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO hub_generation VALUES(1,41)")
            old.execSQL("INSERT INTO hub_preferences VALUES('migration-proof','{\"kept\":true}')")
            old.version = 6
        }

        val database = PersonalHubDatabase.openTemporary(context, name)
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(PersonalHubDatabase.SCHEMA_VERSION, sqlite.version)
            assertEquals("{\"kept\":true}", scalarText(sqlite, "SELECT json FROM hub_preferences WHERE namespace='migration-proof'"))
            assertEquals(52L, scalarLong(sqlite, "SELECT generation FROM hub_generation WHERE id=1"))
            assertEquals(5L, scalarLong(sqlite, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('hub_entity_bindings','hub_contexts','hub_context_members','hub_context_types','hub_context_type_fields')"))
            assertTrue(scalarLong(sqlite, "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='index_hub_context_members_entity_id_context_id'") == 1L)
            assertEquals(1L, scalarLong(sqlite, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='hub_resources'"))
            assertEquals(1L, scalarLong(sqlite, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='hub_activity_log'"))
            sqlite.execSQL("INSERT INTO hub_contexts VALUES('ctx',NULL,NULL,1767225600000,1767225600000)")
            try {
                sqlite.execSQL("INSERT INTO hub_context_members VALUES('ctx','missing','',0)")
                fail("Expected binding foreign-key failure")
            } catch (_: Exception) { }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionEightLocksSystemTypeWithoutChangingUserContextsOrResources() {
        val name = "hub-context-lock-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val entities = JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/8.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("PRAGMA foreign_keys=ON")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO hub_generation VALUES(1,8)")
            old.execSQL("INSERT INTO hub_context_types VALUES('timer_activity','Timer activity','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")
            old.execSQL("INSERT INTO hub_context_types VALUES('user_type','User type','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")
            old.execSQL("INSERT INTO hub_contexts VALUES('ctx','user_type','Kept','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")
            old.execSQL("INSERT INTO hub_resources VALUES('res','NOTE','Kept resource','body',0,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")
            old.version = 8
        }

        val database = PersonalHubDatabase.openTemporary(context, name)
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(PersonalHubDatabase.SCHEMA_VERSION, sqlite.version)
            assertEquals(1L, scalarLong(sqlite, "SELECT locked FROM hub_context_types WHERE id='timer_activity'"))
            assertEquals(0L, scalarLong(sqlite, "SELECT locked FROM hub_context_types WHERE id='user_type'"))
            assertEquals("Kept", scalarText(sqlite, "SELECT title FROM hub_contexts WHERE id='ctx'"))
            assertEquals("Kept resource", scalarText(sqlite, "SELECT title FROM hub_resources WHERE id='res'"))
            assertEquals(17L, scalarLong(sqlite, "SELECT generation FROM hub_generation WHERE id=1"))
            assertEquals(1L, scalarLong(sqlite, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='hub_activity_log'"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun scalarLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) = db.query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun scalarText(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) = db.query(sql).use { it.moveToFirst(); it.getString(0) }
}
