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

    @Test fun realV16TimestampRowsMigrateWithNullsDatesAndForeignKeysIntact() {
        val name = "timestamp-v16-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        createVersion(file, 16)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL("INSERT INTO hub_generation(id,generation) VALUES(1,16)")
            old.execSQL("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('a','Account','EUR','0','2026-01-01T00:00:00.123Z',1)")
            old.execSQL("INSERT INTO finance_transactions(id,accountId,uuid,amount,currency,fromReceipt,notes,occurredAt,createdAt,updatedAt) VALUES(1,'a','tx','1','EUR',0,'note','2026-01-02T01:02:03.456+01:00','2026-01-01T00:00:00Z','1767312123456')")
            old.execSQL("INSERT INTO finance_transactions(id,accountId,uuid,amount,currency,fromReceipt,notes,occurredAt,createdAt,updatedAt,reminderAt) VALUES(2,'a','tx2','-1','EUR',0,'note','2026-01-02T00:02:04Z','2026-01-01T00:00:00Z','2026-01-02T00:02:04Z','2026-01-03T00:00:00Z')")
            old.execSQL("INSERT INTO finance_transfers(id,sourceTransactionId,targetTransactionId,createdAt) VALUES('transfer',1,2,'2026-01-02T00:02:04Z')")
            old.execSQL("INSERT INTO finance_macros(id,title,accountId,currency,occurredAt,notes,createdAt) VALUES('macro','Macro','a','EUR','2026-01-02T00:02:04Z','','2026-01-01T00:00:00Z')")
            old.execSQL("INSERT INTO finance_recurrences(id,title,amount,currency,accountId,chain,notes,lastBusinessDay,startDate,enabled,createdAt,updatedAt,kind,category) VALUES('rec','Recurring','1','EUR','a','','',0,'2026-01-01',1,'2026-01-01T00:00:00Z','2026-01-02T00:02:04Z','INCOME','')")
            old.execSQL("INSERT INTO finance_recurrence_overrides(recurrenceId,occurrenceDate,skipped,transactionId,createdAt) VALUES('rec','2026-01-01',0,1,'2026-01-02T00:02:04Z')")
            old.execSQL("INSERT INTO finance_attachments(id,transactionId,kind,uri,title,createdAt) VALUES('attachment',1,'receipt','content://example','Receipt','2026-01-02T00:02:04Z')")
            old.execSQL("INSERT INTO hub_context_types(id,name,created_at,updated_at,locked) VALUES('type','Type','2026-01-01T00:00:00Z','2026-01-01T00:00:01Z',0)")
            old.execSQL("INSERT INTO hub_contexts(id,context_type_id,title,created_at,updated_at) VALUES('ctx','type','Kept','2026-01-01T00:00:00Z','2026-01-01T00:00:02Z')")
            old.execSQL("INSERT INTO hub_entity_bindings(id,module_id,entity_kind,canonical_id,lifecycle,updated_at) VALUES('binding','soldi','transaction','tx','ACTIVE','2026-01-01T00:00:03Z')")
            old.execSQL("INSERT INTO hub_context_members(context_id,entity_id,role,position) VALUES('ctx','binding','',0)")
            old.execSQL("INSERT INTO hub_resources(id,kind,value,persistedPermission,createdAt,updatedAt) VALUES('res','note','kept',0,'2026-01-01T00:00:00Z','2026-01-01T00:00:04Z')")
            old.execSQL("INSERT INTO substances(id,name,canonical_name,type,stock_current,stock_unit,dose_per_intake,dose_unit,daily_frequency,start_epoch_day,forever,archived,prn) VALUES(1,'Test','test','farmaco',1,'mg',1,'mg',1,20000,1,0,0)")
            old.execSQL("INSERT INTO intake_events(id,substance_id,timestamp_ms,timestamp_utc,dose,dose_unit) VALUES(1,1,1767225600123,'2026-01-01T00:00:00.123Z',1,'mg')")
            old.execSQL("INSERT INTO stock_adjustments(id,substance_id,timestamp_ms,timestamp_utc,delta,resulting_stock) VALUES(1,1,1767225600123,'',-1,0)")
            old.execSQL("INSERT INTO notification_state(id,kind,entity_id,scheduled_for_ms,scheduled_for_utc,sent_at_ms,sent_at_utc) VALUES(1,'dose',1,1767225600123,'2026-01-01T00:00:00.123Z',NULL,NULL)")
            old.execSQL("INSERT INTO notification_state(id,kind,entity_id,scheduled_for_ms,scheduled_for_utc,sent_at_ms,sent_at_utc) VALUES(2,'dose',1,1767312000000,'',1767312124000,'2026-01-02T00:02:04Z')")
            old.execSQL("INSERT INTO prescriptions(id,substance_id,prescription_epoch_day,prescription_date_utc,quantity_prescribed,refill_every_months,alert_refill) VALUES(1,1,20000,'2026-01-01',1,1,0)")
        }
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            val db = owner.openHelper.writableDatabase
            assertEquals(17, db.version)
            assertEquals(1767225600123L, scalar(db, "SELECT openedAt FROM finance_accounts WHERE id='a'"))
            assertEquals(1767312123456L, scalar(db, "SELECT occurredAt FROM finance_transactions WHERE id=1"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM finance_transactions WHERE reminderAt IS NULL"))
            assertEquals(1767398400000L, scalar(db, "SELECT reminderAt FROM finance_transactions WHERE id=2"))
            assertEquals(1767312124000L, scalar(db, "SELECT createdAt FROM finance_transfers WHERE id='transfer'"))
            assertEquals(1767312124000L, scalar(db, "SELECT occurredAt FROM finance_macros WHERE id='macro'"))
            assertEquals(1767312124000L, scalar(db, "SELECT updatedAt FROM finance_recurrences WHERE id='rec'"))
            assertEquals("2026-01-01", scalarText(db, "SELECT startDate FROM finance_recurrences WHERE id='rec'"))
            assertEquals(1767312124000L, scalar(db, "SELECT createdAt FROM finance_recurrence_overrides WHERE recurrenceId='rec'"))
            assertEquals(1767312124000L, scalar(db, "SELECT createdAt FROM finance_attachments WHERE id='attachment'"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_context_members WHERE context_id='ctx' AND entity_id='binding'"))
            assertEquals(1767225600123L, scalar(db, "SELECT timestamp_utc FROM stock_adjustments WHERE id=1"))
            assertEquals(1767312124000L, scalar(db, "SELECT sent_at_utc FROM notification_state WHERE id=2"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM notification_state WHERE id=1 AND sent_at_utc IS NULL"))
            assertEquals("2026-01-01", scalarText(db, "SELECT prescription_date_utc FROM prescriptions WHERE id=1"))
            assertEquals("ok", scalarText(db, "PRAGMA quick_check"))
            assertFalse(db.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        } finally { owner.close(); context.deleteDatabase(name) }
    }

    @Test fun malformedV16InstantFailsClosedWithoutLosingDatabase() {
        val name = "timestamp-invalid-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        createVersion(file, 16)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('bad','Bad','EUR','0','tomorrow',1)")
        }
        try {
            val owner = PersonalHubDatabase.openTemporary(context, name)
            try { owner.openHelper.writableDatabase; fail("Expected invalid legacy instant") }
            catch (_: java.time.format.DateTimeParseException) { }
            finally { owner.close() }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(16, old.version)
                assertEquals("tomorrow", old.rawQuery("SELECT openedAt FROM finance_accounts WHERE id='bad'", null).use { it.moveToFirst(); it.getString(0) })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun legacyParserAcceptsOnlyExactMillisecondInstants() {
        assertEquals(1767312123456L, TimestampEpochMigration.exactEpochMs("2026-01-02T01:02:03.456+01:00"))
        assertEquals(1767312123456L, TimestampEpochMigration.exactEpochMs("1767312123456"))
        assertThrows(IllegalArgumentException::class.java) {
            TimestampEpochMigration.exactEpochMs("2026-01-02T00:02:03.456789Z")
        }
        assertThrows(java.time.format.DateTimeParseException::class.java) {
            TimestampEpochMigration.exactEpochMs("2026-01-02")
        }
    }

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
                if (version == 15) {
                    assertTrue(tableExists(db, "place_tags"))
                    assertTrue(tableExists(db, "place_tag_cross_ref"))
                    assertTrue(tableExists(db, "alert_rules"))
                    assertTrue(tableExists(db, "alert_place_tag_targets"))
                    try {
                        db.execSQL("INSERT INTO place_tag_cross_ref(place_uuid,tag_id) VALUES('missing',1)")
                        fail("Expected place tag foreign-key failure")
                    } catch (_: Exception) { }
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

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String) =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) =
        db.query(sql).use { assertTrue(it.moveToFirst()); it.getLong(0) }

    private fun scalarText(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) =
        db.query(sql).use { assertTrue(it.moveToFirst()); it.getString(0) }
}
