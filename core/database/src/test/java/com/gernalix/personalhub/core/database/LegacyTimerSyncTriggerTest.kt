package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyTimerSyncTriggerTest {
    @Test fun validLegacyTriggersPassStartupValidationAndAreRetiredOnOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = PersonalHubDatabase.DB_NAME
        val file = context.getDatabasePath(name)
        try {
            PersonalHubDatabase.closeInstance()
            context.deleteDatabase(name)
            PersonalHubDatabase.openTemporary(context, name).let { owner ->
                try {
                    owner.openHelper.writableDatabase
                } finally { owner.close() }
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                LEGACY_TIMER_SYNC_TABLES.forEach { table ->
                    listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                        db.execSQL("CREATE TRIGGER `hub_dirty_${table}_$op` AFTER $op ON `$table` BEGIN UPDATE hub_generation SET generation=generation+1 WHERE id=1; END")
                    }
                }
            }
            DatabaseVault.preferences(context).edit().remove("startup_gate_schema").remove("startup_gate_app_version").commit()
            assertTrue(DatabaseVault.ensureStartupReady(context))
            PersonalHubDatabase.openTemporary(context, name).let { owner ->
                try {
                    val db = owner.openHelper.writableDatabase
                    assertEquals(0, db.query("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'hub_dirty_sync_%'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getInt(0)
                    })
                } finally { owner.close() }
            }
            assertTrue(DatabaseVault.validate(context, file) >= 0)
        } finally {
            PersonalHubDatabase.closeInstance()
            context.deleteDatabase(name)
            DatabaseVault.preferences(context).edit().remove("startup_gate_schema").remove("startup_gate_app_version").commit()
        }
    }
}
