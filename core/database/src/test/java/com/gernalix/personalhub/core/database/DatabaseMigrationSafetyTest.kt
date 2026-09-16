package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun productionRegistryIsTheRealContinuousGraph() {
        val edges = PersonalHubDatabase.productionMigrationEdges(context)
        assertEquals((1 until PersonalHubDatabase.SCHEMA_VERSION).map { it to it + 1 }.toSet(), edges)
        assertTrue(PersonalHubDatabase.canMigrateFrom(1))
        assertTrue(PersonalHubDatabase.canMigrateFrom(PersonalHubDatabase.SCHEMA_VERSION))
        assertFalse(PersonalHubDatabase.canMigrateFrom(0))
        assertFalse(PersonalHubDatabase.canMigrateFrom(PersonalHubDatabase.SCHEMA_VERSION + 1))
    }

    @Test fun everyStoredRoomSnapshotMigratesAndWordPulseV4SurvivesReopen() {
        for (version in 1 until PersonalHubDatabase.SCHEMA_VERSION) {
            val name = "historical-$version-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
            val entities = JSONObject(
                context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/$version.json")
                    .bufferedReader().use { it.readText() },
            ).getJSONObject("database").getJSONArray("entities")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("PRAGMA foreign_keys=OFF")
                createSchema(old, entities)
                if (tableExists(old, "hub_generation")) {
                    old.execSQL("INSERT OR REPLACE INTO hub_generation(id,generation) VALUES(1,?)", arrayOf(10_000 + version))
                }
                if (version == 12) {
                    old.execSQL("INSERT INTO wordpulse_sessions(id,started_at_utc_ms,ended_at_utc_ms) VALUES('fatigue-session',1000,2000)")
                    old.execSQL("INSERT INTO word_entries(original_word,normalized_word,created_at_utc_ms,session_id) VALUES('test','test',1500,'fatigue-session')")
                }
                if (version == 14) {
                    old.execSQL("INSERT INTO places(uuid,nickname,created_at,updated_at,archived) VALUES('candidate-place','Candidate Place',1000,1000,0)")
                    old.execSQL("INSERT INTO check_in_attempts(id,started_at,source,stage,outcome) VALUES('attempt-14',2000,'Luoghi','AMBIGUOUS','AMBIGUOUS')")
                    old.execSQL(
                        "INSERT INTO check_in_attempt_candidates(attempt_id,place_id,distance_m,threshold_m,rank,result) VALUES('attempt-14','candidate-place',12.5,75.0,1,'AMBIGUOUS')",
                    )
                }
                old.version = version
            }
            val owner = PersonalHubDatabase.openTemporary(context, file.absolutePath)
            try {
                val db = owner.openHelper.writableDatabase
                assertEquals("snapshot $version", PersonalHubDatabase.SCHEMA_VERSION, db.version)
                assertTrue("snapshot $version", !db.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
                if (version == 12) {
                    assertEquals(1L, scalar(db, "SELECT count(*) FROM word_entries WHERE session_id='fatigue-session'"))
                    db.execSQL("UPDATE word_entries SET fatigue_score=37 WHERE session_id='fatigue-session'")
                    db.execSQL("INSERT INTO pvt_results(started_at_utc_ms,completed_at_utc_ms,duration_ms,trial_count,lapse_count,false_start_count,paired_fatigue_score) VALUES(1,2,1,1,0,0,37)")
                }
                if (version == 14) {
                    assertEquals(1L, scalar(db, "SELECT count(*) FROM check_in_attempts WHERE id='attempt-14'"))
                    assertEquals(1L, scalar(db, "SELECT count(*) FROM check_in_attempt_candidates WHERE attempt_id='attempt-14'"))
                    assertEquals("Candidate Place", scalarText(db, "SELECT place_name_snapshot FROM check_in_attempt_candidates WHERE attempt_id='attempt-14'"))
                    assertEquals(0L, scalar(db, "SELECT count(*) FROM place_events"))
                    db.execSQL("DELETE FROM places WHERE uuid='candidate-place'")
                    assertEquals(1L, scalar(db, "SELECT count(*) FROM check_in_attempt_candidates WHERE attempt_id='attempt-14'"))
                    assertEquals("Candidate Place", scalarText(db, "SELECT place_name_snapshot FROM check_in_attempt_candidates WHERE attempt_id='attempt-14'"))
                }
            } finally { owner.close() }
            if (version == 12) {
                val reopened = PersonalHubDatabase.openTemporary(context, file.absolutePath)
                try {
                    val db = reopened.openHelper.readableDatabase
                    assertEquals(37L, scalar(db, "SELECT fatigue_score FROM word_entries WHERE session_id='fatigue-session'"))
                    assertEquals(37L, scalar(db, "SELECT paired_fatigue_score FROM pvt_results"))
                } finally { reopened.close() }
            }
            context.deleteDatabase(name)
        }
    }

    @Test fun startupGateSnapshotsMigratesValidV12AndMemoizesSuccess() {
        val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        val snapshot = java.io.File(target.parentFile, "personalhub-startup-v12.db")
        resetCanonical(target, snapshot)
        try {
            createVersion(target, 12)
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("INSERT INTO wordpulse_sessions(id,started_at_utc_ms) VALUES('startup-kept',1000)")
                db.execSQL("INSERT INTO word_entries(original_word,normalized_word,created_at_utc_ms,session_id) VALUES('kept','kept',1000,'startup-kept')")
            }
            assertTrue(DatabaseVault.ensureStartupReady(context))
            assertTrue(snapshot.isFile)
            assertTrue(DatabaseVault.ensureStartupReady(context))
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(PersonalHubDatabase.SCHEMA_VERSION, db.version)
                assertEquals(1, db.rawQuery("SELECT count(*) FROM word_entries WHERE original_word='kept'", null).use { it.moveToFirst(); it.getInt(0) })
            }
        } finally { resetCanonical(target, snapshot) }
    }

    @Test fun startupGateRollsBackFailedMigrationWithoutReplacingData() {
        val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        val snapshot = java.io.File(target.parentFile, "personalhub-startup-v12.db")
        resetCanonical(target, snapshot)
        try {
            createVersion(target, 12)
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("ALTER TABLE word_entries ADD COLUMN fatigue_score INTEGER")
            }
            assertFalse(DatabaseVault.ensureStartupReady(context))
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(12, db.version)
                assertTrue(db.rawQuery("PRAGMA table_info(word_entries)", null).use { c ->
                    var found = false
                    while (c.moveToNext()) if (c.getString(1) == "fatigue_score") found = true
                    found
                })
            }
            assertTrue(DatabaseVault.error(context)?.contains("rolled back") == true)
        } finally { resetCanonical(target, snapshot) }
    }

    private fun createVersion(file: java.io.File, version: Int) {
        val entities = JSONObject(
            context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/$version.json")
                .bufferedReader().use { it.readText() },
        ).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            createSchema(db, entities)
            db.version = version
        }
    }

    private fun resetCanonical(target: java.io.File, snapshot: java.io.File) {
        PersonalHubDatabase.closeInstance()
        listOf(target, snapshot).forEach { file ->
            listOf("", "-wal", "-shm", "-journal").forEach { suffix -> java.io.File(file.path + suffix).delete() }
        }
        DatabaseVault.preferences(context).edit()
            .remove("startup_gate_schema").remove("startup_gate_app_version").remove("error").commit()
    }

    private fun createSchema(db: SQLiteDatabase, entities: JSONArray) {
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String) =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) =
        db.query(sql).use { assertTrue(it.moveToFirst()); it.getLong(0) }

    private fun scalarText(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) =
        db.query(sql).use { assertTrue(it.moveToFirst()); it.getString(0) }
}
