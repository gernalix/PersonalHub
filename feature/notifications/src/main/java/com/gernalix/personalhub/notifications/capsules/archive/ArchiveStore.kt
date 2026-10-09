package com.gernalix.personalhub.notifications.capsules.archive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

internal data class ArchiveEvent(val id: Long, val kind: String, val snapshot: JSONObject, val reason: Int?)
internal class ArchiveStore(context: Context) : SQLiteOpenHelper(context,
    File(context.noBackupFilesDir, "notifications.sqlite").absolutePath, null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, package TEXT NOT NULL, nkey TEXT NOT NULL, captured TEXT NOT NULL, payload TEXT NOT NULL, reason INTEGER)")
        db.execSQL("CREATE INDEX events_time ON events(captured DESC,id DESC)")
        db.execSQL("CREATE INDEX events_app_time ON events(package,captured DESC)")
        db.execSQL("CREATE INDEX events_key ON events(nkey,id DESC)")
        db.execSQL("CREATE TABLE active(nkey TEXT PRIMARY KEY)")
        db.execSQL("CREATE VIRTUAL TABLE event_search USING fts4(content)")
        db.execSQL("CREATE TRIGGER immutable_update BEFORE UPDATE ON events BEGIN SELECT RAISE(ABORT,'immutable event'); END")
        db.execSQL("CREATE TRIGGER immutable_delete BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT,'immutable event'); END")
        NormalizedArchive.create(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion==1 && newVersion==2); NormalizedArchive.create(db)
    }
    @Synchronized fun normalizePending(limit: Int = 100): Int = NormalizedArchive.process(writableDatabase,limit)
    @Synchronized fun append(snapshot: JSONObject, requested: String, reason: Int? = null): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val key = snapshot.getString("key")
            val known = db.rawQuery("SELECT 1 FROM active WHERE nkey=?", arrayOf(key)).use { it.moveToFirst() }
            val kind = if (requested == "POSTED" && known) "UPDATED" else requested
            val id = db.insertOrThrow("events", null, ContentValues().apply {
                put("kind",kind); put("package", snapshot.getString("package")); put("nkey",key)
                put("captured",utc(snapshot.getString("captured"))); put("payload",snapshot.toString())
                if (reason != null) put("reason",reason)
            })
            db.execSQL("INSERT INTO event_search(docid,content) VALUES(?,?)", arrayOf(id, searchable(snapshot)))
            if (requested == "REMOVED") db.delete("active", "nkey=?", arrayOf(key))
            else db.execSQL("INSERT OR IGNORE INTO active(nkey) VALUES(?)", arrayOf(key))
            db.setTransactionSuccessful()
            changes.value += 1
            return id
        } finally { db.endTransaction() }
    }
    /** Missing callbacks during a gap are not fabricated as REMOVED. */
    @Synchronized fun connected() { writableDatabase.delete("active",null,null) }
    @Synchronized fun event(id: Long): ArchiveEvent? = readableDatabase.rawQuery("SELECT id,kind,payload,reason FROM events WHERE id=?",arrayOf(id.toString())).use { c ->
        if (!c.moveToFirst()) null else ArchiveEvent(c.getLong(0),c.getString(1),JSONObject(c.getString(2)),if(c.isNull(3)) null else c.getInt(3))
    }
    @Synchronized fun query(search: String = "", app: String = "", from: String = "", until: String = "", before: Long = Long.MAX_VALUE): List<ArchiveEvent> {
        val clauses = mutableListOf("1=1"); val args = mutableListOf<String>()
        if(before != Long.MAX_VALUE) {
            clauses += "(captured < (SELECT captured FROM events WHERE id=?) OR (captured = (SELECT captured FROM events WHERE id=?) AND id<?))"
            args += listOf(before.toString(),before.toString(),before.toString())
        }
        if(app.isNotBlank()){clauses += "package=?";args+=app}
        if(from.isNotBlank()){clauses += "captured>=?";args+=utc(from)}
        if(until.isNotBlank()){clauses += "captured<?";args+=utc(until)}
        val terms = Regex("[\\p{L}\\p{N}_]+").findAll(search).map { "\"${it.value}\"*" }.toList()
        if(terms.isNotEmpty()){clauses += "id IN (SELECT docid FROM event_search WHERE content MATCH ?)";args+=terms.joinToString(" AND ")}
        return readableDatabase.rawQuery("SELECT id,kind,payload,reason FROM events WHERE ${clauses.joinToString(" AND ")} ORDER BY captured DESC,id DESC LIMIT 100",args.toTypedArray()).use { c ->
            buildList { while(c.moveToNext())add(ArchiveEvent(c.getLong(0),c.getString(1),JSONObject(c.getString(2)),if(c.isNull(3)) null else c.getInt(3))) }
        }
    }
    @Synchronized fun apps(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT package FROM events ORDER BY package",null).use { c -> buildList { while(c.moveToNext())add(c.getString(0)) } }
    /** VACUUM creates a coherent independent SQLite file; WAL/live files are never exported. */
    @Synchronized fun export(output: OutputStream) {
        val file = File(databaseName + ".export")
        try {
            file.delete()
            writableDatabase.execSQL("VACUUM INTO ?", arrayOf(file.absolutePath))
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                val result = db.rawQuery("PRAGMA quick_check",null).use { if(it.moveToFirst()) it.getString(0) else "no result" }
                check(result == "ok") { "Export integrity: $result" }
            }
            file.inputStream().use { it.copyTo(output) }
        } finally { file.delete() }
    }
    companion object {
        val changes = MutableStateFlow(0L)
        @Volatile private var instance: ArchiveStore? = null
        fun get(context: Context): ArchiveStore = instance ?: synchronized(this) { instance ?: ArchiveStore(context.applicationContext).also { instance=it } }
        private fun searchable(s: JSONObject): String = listOf("title","text","bigText","lines","messages","historicMessages").joinToString("\n") { s.optString(it) }
    }
}
