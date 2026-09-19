package com.gernalix.personalhub.core.database.capsules.health

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** Room does not create unmanaged consumer views for a fresh database. */
object HealthViews {
    fun install(context: Context, db: SupportSQLiteDatabase) {
        val text=context.assets.open("personalhub_migrations/17_18_health.json").bufferedReader().use { it.readText() }
        val statements=JSONObject(text).getJSONArray("statements")
        for(i in 0 until statements.length()) {
            val sql=statements.getString(i)
            if(sql.startsWith("CREATE VIEW IF NOT EXISTS `v_health_")) db.execSQL(sql)
        }
    }
}
