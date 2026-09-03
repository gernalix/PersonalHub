package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** Add empty finance tables. Never open, attach, copy or seed from standalone Soldi. */
class FinanceMigration(private val context: Context) : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val entities = JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/4.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            if (!table.startsWith("finance_")) continue
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.getJSONArray("indices")
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        // Schema-only changes must also reach the next validated SAF export.
        db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
    }
}
