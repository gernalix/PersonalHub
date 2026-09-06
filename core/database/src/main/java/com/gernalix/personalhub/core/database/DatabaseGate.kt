package com.gernalix.personalhub.core.database

import android.content.ContentValues
import android.content.Context
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes writers, including legacy timer writes, and quiesces them during file replacement. */
object DatabaseGate {
    private val lock = ReentrantLock(true)
    private val resumed = lock.newCondition()
    private val privileged = ThreadLocal.withInitial { false }
    private val transactionDepth = ThreadLocal.withInitial { 0 }
    private val mutatingTransactionDepth = ThreadLocal.withInitial { 0 }
    @Volatile private var frozen = false
    @Volatile private var autoExportContext: Context? = null
    fun configureAutoExport(context: Context) {
        autoExportContext = context.applicationContext
    }
    fun <T> access(block: () -> T): T = lock.withLock {
        while (frozen && privileged.get() != true) resumed.await()
        block()
    }
    fun afterMutation() {
        if (privileged.get() != true) autoExportContext?.let {
            HubAutoExport.requestIfDirty(it)
            DatasetteSync.checkForChanges(it)
        }
    }
    fun begin(mutating: Boolean = true, block: () -> Unit) {
        lock.lock()
        try { while (frozen && privileged.get() != true) resumed.await(); block() }
        catch (error: Throwable) { lock.unlock(); throw error }
        transactionDepth.set((transactionDepth.get() ?: 0) + 1)
        if (mutating) mutatingTransactionDepth.set((mutatingTransactionDepth.get() ?: 0) + 1)
    }
    fun end(block: () -> Unit) {
        try { block() } finally {
            val depth = (transactionDepth.get() ?: 0) - 1
            val mutatingDepth = mutatingTransactionDepth.get() ?: 0
            transactionDepth.set(depth.coerceAtLeast(0))
            if (mutatingDepth > 0) mutatingTransactionDepth.set(mutatingDepth - 1)
            lock.unlock()
            if (depth <= 0 && mutatingDepth > 0) afterMutation()
        }
    }
    fun <T> mutate(block: () -> T): T {
        return try { access(block) } finally { if ((transactionDepth.get() ?: 0) == 0) afterMutation() }
    }
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
    override fun beginTransactionReadOnly() = DatabaseGate.begin(mutating = false) { delegate.beginTransactionReadOnly() }
    override fun beginTransactionWithListenerReadOnly(transactionListener: android.database.sqlite.SQLiteTransactionListener) = DatabaseGate.begin(mutating = false) { delegate.beginTransactionWithListenerReadOnly(transactionListener) }
    override fun endTransaction() = DatabaseGate.end { delegate.endTransaction() }
    override fun execSQL(sql: String) = DatabaseGate.mutate { delegate.execSQL(sql) }
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = DatabaseGate.mutate { delegate.execSQL(sql, bindArgs) }
    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues) = DatabaseGate.mutate { delegate.insert(table, conflictAlgorithm, values) }
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?) = DatabaseGate.mutate { delegate.delete(table, whereClause, whereArgs) }
    override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?) = DatabaseGate.mutate { delegate.update(table, conflictAlgorithm, values, whereClause, whereArgs) }
    override fun compileStatement(sql: String): SupportSQLiteStatement {
        val statement = delegate.compileStatement(sql)
        return object : SupportSQLiteStatement by statement {
            override fun execute() = DatabaseGate.mutate { statement.execute() }
            override fun executeInsert() = DatabaseGate.mutate { statement.executeInsert() }
            override fun executeUpdateDelete() = DatabaseGate.mutate { statement.executeUpdateDelete() }
        }
    }
}
