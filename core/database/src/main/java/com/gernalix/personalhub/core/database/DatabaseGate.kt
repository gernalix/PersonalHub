package com.gernalix.personalhub.core.database

import android.content.ContentValues
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes writers, including legacy timer writes, and quiesces them during file replacement. */
object DatabaseGate {
    private val lock = ReentrantLock(true)
    private val resumed = lock.newCondition()
    private val privileged = ThreadLocal.withInitial { false }
    @Volatile private var frozen = false
    fun <T> access(block: () -> T): T = lock.withLock {
        while (frozen && !privileged.get()) resumed.await()
        block()
    }
    fun begin(block: () -> Unit) {
        lock.lock()
        try { while (frozen && !privileged.get()) resumed.await(); block() }
        catch (error: Throwable) { lock.unlock(); throw error }
    }
    fun end(block: () -> Unit) { try { block() } finally { lock.unlock() } }
    fun <T> replace(block: () -> T): T = lock.withLock {
        frozen = true
        privileged.set(true)
        try { block() } finally { privileged.set(false) }
    }
    fun resume() = lock.withLock { frozen = false; resumed.signalAll() }
}

class GatedOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = DatabaseGate.access { GatedDatabase(helper.writableDatabase) }
            override val readableDatabase get() = DatabaseGate.access { GatedDatabase(helper.readableDatabase) }
        }
    }
}

private class GatedDatabase(private val delegate: SupportSQLiteDatabase) : SupportSQLiteDatabase by delegate {
    override fun beginTransaction() = DatabaseGate.begin { delegate.beginTransaction() }
    override fun beginTransactionNonExclusive() = DatabaseGate.begin { delegate.beginTransactionNonExclusive() }
    override fun beginTransactionWithListener(transactionListener: android.database.sqlite.SQLiteTransactionListener) = DatabaseGate.begin { delegate.beginTransactionWithListener(transactionListener) }
    override fun beginTransactionWithListenerNonExclusive(transactionListener: android.database.sqlite.SQLiteTransactionListener) = DatabaseGate.begin { delegate.beginTransactionWithListenerNonExclusive(transactionListener) }
    override fun beginTransactionReadOnly() = DatabaseGate.begin { delegate.beginTransactionReadOnly() }
    override fun beginTransactionWithListenerReadOnly(transactionListener: android.database.sqlite.SQLiteTransactionListener) = DatabaseGate.begin { delegate.beginTransactionWithListenerReadOnly(transactionListener) }
    override fun endTransaction() = DatabaseGate.end { delegate.endTransaction() }
    override fun execSQL(sql: String) = DatabaseGate.access { delegate.execSQL(sql) }
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = DatabaseGate.access { delegate.execSQL(sql, bindArgs) }
    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues) = DatabaseGate.access { delegate.insert(table, conflictAlgorithm, values) }
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?) = DatabaseGate.access { delegate.delete(table, whereClause, whereArgs) }
    override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?) = DatabaseGate.access { delegate.update(table, conflictAlgorithm, values, whereClause, whereArgs) }
    override fun compileStatement(sql: String): SupportSQLiteStatement {
        val statement = delegate.compileStatement(sql)
        return object : SupportSQLiteStatement by statement {
            override fun execute() = DatabaseGate.access { statement.execute() }
            override fun executeInsert() = DatabaseGate.access { statement.executeInsert() }
            override fun executeUpdateDelete() = DatabaseGate.access { statement.executeUpdateDelete() }
        }
    }
}
