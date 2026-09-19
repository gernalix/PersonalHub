package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseProfilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        resetStorage()
        DatabaseVault.setDirectorySyncForTests { }
    }

    @After
    fun tearDown() {
        resetStorage()
        DatabaseVault.setDirectorySyncForTests(null)
    }

    @Test
    fun recoveryWithoutJournalRetiresOrphanedSwitchIntent() {
        PersonalHubDatabase.get(context).openHelper.writableDatabase
        val target = DatabaseProfiles.create(context, "Work", DatabaseProfileInitMode.EMPTY)
        context.getSharedPreferences("personalhub_profiles", Context.MODE_PRIVATE)
            .edit()
            .putString("pending_switch_from", DatabaseProfiles.DEFAULT_PROFILE_ID)
            .putString("pending_switch_to", target.id)
            .commit()

        DatabaseVault.recoverInterruptedImport(context)

        val prefs = context.getSharedPreferences("personalhub_profiles", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("pending_switch_from"))
        assertFalse(prefs.contains("pending_switch_to"))
        assertEquals(DatabaseProfiles.DEFAULT_PROFILE_ID, DatabaseProfiles.activeProfileId(context))
    }

    @Test
    fun defaultProfileUsesCanonicalDatabaseCloneAndEmptyAreIsolated() {
        val canonical = writableDatabase()
        insertCrossModuleFixture(canonical, "personal")
        canonical.execSQL("INSERT OR REPLACE INTO hub_sync_known(table_name,row_key) VALUES('places','personal')")

        assertEquals(DatabaseProfiles.DEFAULT_PROFILE_ID, DatabaseProfiles.activeProfileId(context))
        assertEquals("personal", scalar(canonical, "SELECT title FROM sessions WHERE id=101"))

        val clone = DatabaseProfiles.create(context, "Lavoro", DatabaseProfileInitMode.CLONE_CURRENT)
        val empty = DatabaseProfiles.create(context, "Vuoto", DatabaseProfileInitMode.EMPTY)

        fixtureTables.forEach { table ->
            assertEquals("clone must preserve $table", 1L, detachedCount(DatabaseProfiles.databaseFile(context, clone.id), table))
            assertEquals("empty must not inherit $table", 0L, detachedCount(DatabaseProfiles.databaseFile(context, empty.id), table))
        }
        assertEquals(0L, detachedCount(DatabaseProfiles.databaseFile(context, clone.id), "hub_sync_known"))

        assertTrue(DatabaseProfiles.switch(context, clone.id))
        simulateRestart()
        val cloneDb = writableDatabase()
        cloneDb.execSQL("UPDATE sessions SET title='clone-only' WHERE id=101")

        assertTrue(DatabaseProfiles.switch(context, DatabaseProfiles.DEFAULT_PROFILE_ID))
        simulateRestart()
        assertEquals("personal", scalar(writableDatabase(), "SELECT title FROM sessions WHERE id=101"))

        DatabaseProfiles.rename(context, clone.id, "Lavoro rinominato")
        assertEquals("Lavoro rinominato", DatabaseProfiles.list(context).first { it.id == clone.id }.name)
        DatabaseProfiles.delete(context, clone.id)
        assertFalse(DatabaseProfiles.databaseFile(context, clone.id).exists())
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseProfiles.delete(context, DatabaseProfiles.DEFAULT_PROFILE_ID)
        }
    }

    @Test
    fun everySwitchFaultStageRecoversAConsistentDatabaseProfilePair() {
        faultStages.forEach { stage ->
            resetStorage()
            val canonical = writableDatabase()
            canonical.execSQL("INSERT INTO sessions(id,title,start_ms,end_ms,expected_end_ms,created_at_ms,updated_at_ms,deleted_at_ms) VALUES(101,'current',1,NULL,NULL,1,1,NULL)")
            val target = DatabaseProfiles.create(context, "Target", DatabaseProfileInitMode.CLONE_CURRENT)
            SQLiteDatabase.openDatabase(
                DatabaseProfiles.databaseFile(context, target.id).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { it.execSQL("UPDATE sessions SET title='target' WHERE id=101") }

            DatabaseVault.setTransferHooksForTests(failingHook(stage))
            assertThrows(Throwable::class.java) { DatabaseProfiles.switch(context, target.id) }
            DatabaseVault.setTransferHooksForTests(null)
            simulateRestart(recover = true)

            val active = DatabaseProfiles.activeProfileId(context)
            val mounted = scalar(writableDatabase(), "SELECT title FROM sessions WHERE id=101")
            if (stage == "after_marker_retirement") {
                assertEquals(target.id, active)
                assertEquals("target", mounted)
            } else {
                assertEquals(DatabaseProfiles.DEFAULT_PROFILE_ID, active)
                assertEquals("current", mounted)
            }
        }
    }

    @Test
    fun deviceLocalExportStateAndNamesAreSeparatedByProfile() {
        writableDatabase()
        val clone = DatabaseProfiles.create(context, "Export", DatabaseProfileInitMode.CLONE_CURRENT)
        assertEquals("personalhub", DatabaseProfiles.exportStem(context))
        DatabaseVault.preferences(context).edit().putLong("exported_generation", 11).commit()

        assertTrue(DatabaseProfiles.switch(context, clone.id))
        simulateRestart()
        assertEquals("personalhub-${clone.id}", DatabaseProfiles.exportStem(context))
        assertEquals(-1L, DatabaseVault.exportedGeneration(context))
        DatabaseVault.preferences(context).edit().putLong("exported_generation", 22).commit()

        assertTrue(DatabaseProfiles.switch(context, DatabaseProfiles.DEFAULT_PROFILE_ID))
        simulateRestart()
        assertEquals(11L, DatabaseVault.exportedGeneration(context))
    }

    private fun writableDatabase() = PersonalHubDatabase.get(context).openHelper.writableDatabase

    private fun insertCrossModuleFixture(db: androidx.sqlite.db.SupportSQLiteDatabase, suffix: String) {
        db.execSQL("INSERT INTO contacts(public_id,created_at,updated_at,deleted_at,archived_at) VALUES('person-$suffix',1,1,NULL,NULL)")
        db.execSQL("INSERT INTO places(uuid,nickname,created_at,updated_at,archived) VALUES('place-$suffix','Place',1,1,0)")
        db.execSQL("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('account-$suffix','Wallet','EUR','0','2026-01-01T00:00:00Z',1)")
        db.execSQL("INSERT INTO sessions(id,title,start_ms,end_ms,expected_end_ms,created_at_ms,updated_at_ms,deleted_at_ms) VALUES(101,'$suffix',1,NULL,NULL,1,1,NULL)")
        db.execSQL("INSERT INTO substances(id,name,canonical_name,type,stock_current,stock_unit,dose_per_intake,dose_unit,daily_frequency,start_epoch_day,end_epoch_day,forever,archived,prn,dose_times_csv,days_mask) VALUES(201,'Test','test','other',1,'unit',1,'unit',1,1,NULL,1,0,0,'',127)")
        db.execSQL("INSERT INTO wordpulse_sessions(id,started_at_utc_ms,ended_at_utc_ms) VALUES('word-$suffix',1,NULL)")
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun detachedCount(file: File, table: String): Long =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }

    private fun simulateRestart(recover: Boolean = false) {
        PersonalHubDatabase.closeInstance()
        DatabaseGate.resume()
        if (recover) DatabaseVault.recoverInterruptedImport(context)
    }

    private fun resetStorage() {
        DatabaseVault.setTransferHooksForTests(null)
        DatabaseVault.setDirectorySyncForTests { }
        PersonalHubDatabase.closeInstance()
        DatabaseGate.resume()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        File(context.filesDir, "database-profiles").deleteRecursively()
        File(context.filesDir, "personalhub-import.pending").delete()
        File(context.filesDir, "personalhub-import.pending.damaged").delete()
        context.getSharedPreferences("personalhub_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("personalhub_transfer", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun failingHook(stage: String) = object : DatabaseVault.TransferHooks {
        private fun failAt(name: String) { if (stage == name) error("injected $name") }
        override fun beforeProfileSnapshot() = failAt("before_snapshot")
        override fun afterProfileSnapshot() = failAt("after_snapshot")
        override fun beforeProfileDatabaseRename() = failAt("before_db_rename")
        override fun afterProfileDatabaseRename() = failAt("after_db_rename")
        override fun beforeActiveProfileUpdate() = failAt("before_active_profile")
        override fun afterActiveProfileUpdate() = failAt("after_active_profile")
        override fun beforeProfileMarkerRetirement() = failAt("before_marker_retirement")
        override fun afterProfileMarkerRetirement() = failAt("after_marker_retirement")
    }

    private companion object {
        val fixtureTables = listOf(
            "contacts", "places", "finance_accounts", "sessions", "substances", "wordpulse_sessions",
        )
        val faultStages = listOf(
            "before_snapshot", "after_snapshot", "before_db_rename", "after_db_rename",
            "before_active_profile", "after_active_profile", "before_marker_retirement",
            "after_marker_retirement",
        )
    }
}
