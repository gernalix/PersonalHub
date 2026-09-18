package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Generic migration runner for future schema upgrades.
 *
 * New one-shot upgrade logic belongs in declarative JSON assets, not in Kotlin. Room entities and
 * the @Database version still define which schema the installed APK understands.
 */
object DeclarativeMigrations {
    private const val INDEX = "personalhub_migrations/index.json"

    fun load(context: Context): Array<Migration> {
        val index = context.assets.open(INDEX).bufferedReader().use { JSONObject(it.readText()) }
        require(index.getInt("format_version") == 1) { "Unsupported migration index" }
        val migrations = index.getJSONArray("migrations")
        return Array(migrations.length()) { position ->
            val spec = migrations.getJSONObject(position)
            val from = spec.getInt("from")
            val to = spec.getInt("to")
            val file = spec.getString("file")
            val expectedHash = spec.optString("sha256")
            object : Migration(from, to) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    val bytes = context.assets.open("personalhub_migrations/$file").use { it.readBytes() }
                    if (expectedHash.isNotBlank()) {
                        require(sha256(bytes) == expectedHash) { "Migration asset hash mismatch: $file" }
                    }
                    val document = JSONObject(String(bytes, Charsets.UTF_8))
                    require(document.getInt("format_version") == 1)
                    require(document.getInt("from") == from && document.getInt("to") == to)
                    val statements = document.getJSONArray("statements")
                    for (i in 0 until statements.length()) {
                        db.execSQL(statements.getString(i))
                    }
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
