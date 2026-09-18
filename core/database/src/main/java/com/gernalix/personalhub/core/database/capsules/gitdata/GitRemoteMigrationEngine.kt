package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/**
 * Applies declarative migration documents obtained from the data repository to a staging database.
 * It never migrates the live open Room graph in place.
 */
internal object GitRemoteMigrationEngine {
    fun canMigrate(control: JSONObject?, from: Int, to: Int): Boolean =
        runCatching { resolveChain(control ?: return false, from, to).isNotEmpty() }.getOrDefault(false)

    fun migrate(
        context: Context,
        transport: GitHubDataTransport,
        ref: String,
        control: JSONObject,
        file: java.io.File,
        from: Int,
        to: Int,
    ) {
        val chain = resolveChain(control, from, to)
        require(chain.isNotEmpty() || from == to) { "No remote migration path from $from to $to" }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            var current = from
            for (spec in chain) {
                require(db.version == current) { "Staging database version changed unexpectedly" }
                val bytes = transport.readFile(spec.getString("path"), ref)
                require(GitDataFormat.sha256(bytes) == spec.getString("sha256")) {
                    "Remote migration hash mismatch"
                }
                val document = JSONObject(String(bytes, Charsets.UTF_8))
                require(document.getInt("format_version") == 1)
                require(document.getInt("from") == current)
                val next = document.getInt("to")
                require(next == spec.getInt("to"))

                db.beginTransaction()
                try {
                    val statements = document.getJSONArray("statements")
                    for (i in 0 until statements.length()) db.execSQL(statements.getString(i))
                    db.version = next
                    updateRoomIdentity(context, db, next)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.rawQuery("PRAGMA foreign_key_check", null).use {
                    require(!it.moveToFirst()) { "Remote migration broke foreign keys" }
                }
                db.rawQuery("PRAGMA quick_check", null).use {
                    require(it.moveToFirst() && it.getString(0) == "ok") {
                        "Remote migration failed SQLite integrity check"
                    }
                }
                current = next
            }
            require(current == to)
        }
    }

    private fun resolveChain(control: JSONObject, from: Int, to: Int): List<JSONObject> {
        require(from <= to)
        if (from == to) return emptyList()
        val migrations = control.optJSONArray("migrations") ?: return emptyList()
        val all = buildList {
            for (i in 0 until migrations.length()) add(migrations.getJSONObject(i))
        }
        val chain = mutableListOf<JSONObject>()
        var current = from
        while (current < to) {
            val candidates = all.filter {
                it.getInt("from") == current &&
                    it.getInt("to") > current &&
                    it.getInt("to") <= to
            }
            require(candidates.size == 1) { "Remote migration chain is missing or ambiguous at $current" }
            val next = candidates.single()
            chain += next
            current = next.getInt("to")
        }
        require(current == to)
        return chain
    }

    private fun updateRoomIdentity(
        context: Context,
        db: SQLiteDatabase,
        version: Int,
    ) {
        val schema = JSONObject(
            context.assets.open(
                "com.gernalix.personalhub.core.database.PersonalHubDatabase/$version.json",
            ).bufferedReader().use { it.readText() },
        ).getJSONObject("database")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,?)",
            arrayOf(schema.getString("identityHash")),
        )
    }
}
