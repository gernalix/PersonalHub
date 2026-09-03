package com.gernalix.personalhub.core.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase as AndroidDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.Closeable

/** Timer compatibility API. Canonical calls borrow Room's connection; they never own schema or close it. */
class LegacyDatabase private constructor(
    private val room: SupportSQLiteDatabase? = null,
    private val external: AndroidDatabase? = null,
) : Closeable {
    fun rawQuery(sql: String, args: Array<String>?): Cursor =
        room?.query(sql, args ?: emptyArray()) ?: external!!.rawQuery(sql, args)
    fun query(table: String, columns: Array<String>?, selection: String?, args: Array<String>?, groupBy: String?, having: String?, orderBy: String?): Cursor =
        rawQuery(android.database.sqlite.SQLiteQueryBuilder.buildQueryString(false, table, columns, selection, groupBy, having, orderBy, null), args)
    fun execSQL(sql: String) { room?.execSQL(sql) ?: external!!.execSQL(sql) }
    fun execSQL(sql: String, args: Array<out Any?>) {
        if (room != null) room.execSQL(sql, args) else external!!.execSQL(sql, args)
    }
    fun beginTransaction() { room?.beginTransaction() ?: external!!.beginTransaction() }
    fun beginTransactionNonExclusive() { room?.beginTransactionNonExclusive() ?: external!!.beginTransactionNonExclusive() }
    fun setTransactionSuccessful() { room?.setTransactionSuccessful() ?: external!!.setTransactionSuccessful() }
    fun endTransaction() { room?.endTransaction() ?: external!!.endTransaction() }
    fun inTransaction() = room?.inTransaction() ?: external!!.inTransaction()
    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflict: Int): Long =
        room?.insert(table, conflict, values) ?: external!!.insertWithOnConflict(table, nullColumnHack, values, conflict)
    fun insert(table: String, nullColumnHack: String?, values: ContentValues) = insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE)
    fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues) = insertWithOnConflict(table, nullColumnHack, values, CONFLICT_ABORT)
    fun delete(table: String, where: String?, args: Array<String>?) =
        room?.delete(table, where, args) ?: external!!.delete(table, where, args)
    fun update(table: String, values: ContentValues, where: String?, args: Array<String>?) =
        room?.update(table, CONFLICT_NONE, values, where, args) ?: external!!.update(table, values, where, args)
    val isOpen get() = room?.isOpen ?: external!!.isOpen
    val path get() = room?.path ?: external!!.path
    var version: Int
        get() = room?.version ?: external!!.version
        set(value) { check(room == null) { "Only Room owns the canonical schema version" }; external!!.version = value }
    override fun close() { external?.close() }
    companion object {
        const val CONFLICT_NONE = AndroidDatabase.CONFLICT_NONE
        const val CONFLICT_ABORT = AndroidDatabase.CONFLICT_ABORT
        const val CONFLICT_REPLACE = AndroidDatabase.CONFLICT_REPLACE
        const val CONFLICT_IGNORE = AndroidDatabase.CONFLICT_IGNORE
        const val OPEN_READONLY = AndroidDatabase.OPEN_READONLY
        const val OPEN_READWRITE = AndroidDatabase.OPEN_READWRITE
        const val CREATE_IF_NECESSARY = AndroidDatabase.CREATE_IF_NECESSARY
        fun deleteDatabase(file: java.io.File): Boolean {
            require(file.name != PersonalHubDatabase.DATABASE_NAME) { "Canonical replacement belongs to the database capsule" }
            return AndroidDatabase.deleteDatabase(file)
        }
        fun get(context: Context) = LegacyDatabase(room = PersonalHubDatabase.get(context).openHelper.writableDatabase)
        fun openDatabase(path: String, factory: AndroidDatabase.CursorFactory?, flags: Int) =
            LegacyDatabase(external = AndroidDatabase.openDatabase(path, factory, flags))
        fun openOrCreateDatabase(file: java.io.File, factory: AndroidDatabase.CursorFactory?) =
            LegacyDatabase(external = AndroidDatabase.openOrCreateDatabase(file, factory))
    }
}
