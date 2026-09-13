package com.gernalix.personalhub

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.DatabaseVault
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseVaultLegacyTableValidationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun inertLegacyTableIsAllowedButUnexpectedTriggerIsRejected() {
        val snapshot = DatabaseVault.backupCurrent(context)
        try {
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TABLE `legacy_export_qa` (`id` INTEGER PRIMARY KEY, `value` TEXT)")
            }

            assertTrue(DatabaseVault.validate(context, snapshot) >= 0L)

            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "CREATE TRIGGER `legacy_export_qa_rogue` AFTER INSERT ON `legacy_export_qa` BEGIN SELECT 1; END",
                )
            }
            val error = runCatching { DatabaseVault.validate(context, snapshot) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error?.message?.contains("Unexpected database trigger") == true)
        } finally {
            snapshot.delete()
        }
    }
}
