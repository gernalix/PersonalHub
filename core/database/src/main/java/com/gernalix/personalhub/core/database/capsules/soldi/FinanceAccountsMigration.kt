package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import org.json.JSONObject
import java.util.UUID

/** Rebuild only finance transactions, retaining their IDs and tag links. */
class FinanceAccountsMigration(private val context: Context) : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val entities = JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/5.json").bufferedReader().use { it.readText() }).getJSONObject("database").getJSONArray("entities")
        val schema = (0 until entities.length()).associate { entities.getJSONObject(it).let { e -> e.getString("tableName") to e } }
        fun create(table: String, asName: String = table) { db.execSQL(schema.getValue(table).getString("createSql").replace("\${TABLE_NAME}", asName)) }
        fun indices(table: String) {
            val list = schema.getValue(table).optJSONArray("indices") ?: return
            for (i in 0 until list.length()) db.execSQL(list.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        create("finance_accounts")
        val accounts = mutableMapOf<String, String>()
        db.query("SELECT DISTINCT currency FROM finance_transactions").use { c -> while (c.moveToNext()) {
            val currency = c.getString(0)
            val id = UUID.nameUUIDFromBytes(("finance-default:" + currency).toByteArray()).toString()
            accounts[currency] = id
            db.execSQL("INSERT INTO finance_accounts VALUES (?,?,?,?,?,?)", arrayOf(id,currency,currency,"0","1970-01-01T00:00:00Z",1))
        } }
        db.execSQL("ALTER TABLE finance_products ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        db.query("SELECT id,name FROM finance_products").use { c -> while(c.moveToNext()) {
            val uuid = UUID.nameUUIDFromBytes(("finance-product:" + c.getLong(0) + ":" + c.getString(1)).toByteArray()).toString()
            db.execSQL("UPDATE finance_products SET uuid=? WHERE id=?", arrayOf(uuid,c.getLong(0)))
        } }
        db.execSQL("CREATE UNIQUE INDEX index_finance_products_uuid ON finance_products(uuid)")
        create("finance_transactions", "finance_transactions_v5")
        db.query("SELECT * FROM finance_transactions").use { c ->
            val names = c.columnNames.toList()
            while(c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow("id"))
                val values = names.mapIndexed { i,_ -> when(c.getType(i)) { android.database.Cursor.FIELD_TYPE_NULL -> null; android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i); else -> c.getString(i) } }
                val uuid = UUID.nameUUIDFromBytes(("finance-transaction:" + id + ":" + c.getString(c.getColumnIndexOrThrow("createdAt"))).toByteArray()).toString()
                val columns = names.joinToString(",") { "`$it`" }
                db.execSQL("INSERT INTO finance_transactions_v5 ($columns,accountId,uuid) VALUES (${List(names.size + 2) { "?" }.joinToString(",")})", (values + accounts.getValue(c.getString(c.getColumnIndexOrThrow("currency"))) + uuid).toTypedArray())
            }
        }
        db.execSQL("CREATE TEMP TABLE finance_tags_backup AS SELECT * FROM finance_transaction_tags")
        db.execSQL("DROP TABLE finance_transaction_tags")
        db.execSQL("DROP TABLE finance_transactions")
        db.execSQL("ALTER TABLE finance_transactions_v5 RENAME TO finance_transactions")
        indices("finance_transactions")
        create("finance_transaction_tags"); indices("finance_transaction_tags")
        db.execSQL("INSERT INTO finance_transaction_tags SELECT * FROM finance_tags_backup")
        db.execSQL("DROP TABLE finance_tags_backup")
        db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
        SyncJournal.install(db)
        SyncJournal.enqueueAll(db)
    }
}
