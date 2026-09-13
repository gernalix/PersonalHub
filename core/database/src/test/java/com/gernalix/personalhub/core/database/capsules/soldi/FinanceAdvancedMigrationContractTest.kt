package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinanceAdvancedMigrationContractTest {
    @Test
    fun migrationTargetsCurrentDatabaseSchemaVersion() {
        val migration = FinanceAdvancedMigration()
        assertEquals(11, migration.startVersion)
        assertEquals(12, migration.endVersion)
        assertEquals(12, PersonalHubDatabase.SCHEMA_VERSION)
    }

    @Test
    fun migratesElevenToTwelveWithRepresentativeFinanceRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "finance-11-12-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val entities = JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/11.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            createSchema(old, entities)
            old.execSQL("INSERT INTO hub_generation VALUES(1,100)")
            old.execSQL("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('eur','EUR wallet','EUR','10','2026-01-01T00:00:00Z',1)")
            old.execSQL("INSERT INTO finance_titles(id,name) VALUES(1,'Coffee')")
            old.execSQL(
                "INSERT INTO finance_transactions(id,accountId,uuid,titleId,productId,amount,currency,chainId,placeId,fromReceipt,notes,occurredAt,createdAt,updatedAt) " +
                    "VALUES(1,'eur','txn-1',1,NULL,'-3.50','EUR',NULL,NULL,0,'kept','2026-09-13T08:00:00Z','2026-09-13T08:00:00Z','2026-09-13T08:00:00Z')",
            )
            old.version = 11
        }

        val database = PersonalHubDatabase.openTemporary(context, name)
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(PersonalHubDatabase.SCHEMA_VERSION, sqlite.version)
            assertEquals(1L, scalarLong(sqlite, "SELECT count(*) FROM finance_transactions"))
            assertEquals("Coffee", scalarText(sqlite, "SELECT name FROM finance_titles WHERE id=1"))
            assertEquals("-3.50", scalarText(sqlite, "SELECT amount FROM finance_transactions WHERE id=1"))
            assertEquals("", scalarText(sqlite, "SELECT category FROM finance_transactions WHERE id=1"))
            assertFalse(columnNullable(sqlite, "finance_transactions", "category"))
            assertEquals(1L, scalarLong(sqlite, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='finance_transfers'"))
            assertEquals(101L, scalarLong(sqlite, "SELECT generation FROM hub_generation WHERE id=1"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
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

    private fun scalarLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) = db.query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun scalarText(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) = db.query(sql).use { it.moveToFirst(); it.getString(0) }
    private fun columnNullable(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String, column: String) = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        while (cursor.moveToNext()) if (cursor.getString(1) == column) return@use cursor.getInt(3) == 0
        error("Missing column $table.$column")
    }
}
