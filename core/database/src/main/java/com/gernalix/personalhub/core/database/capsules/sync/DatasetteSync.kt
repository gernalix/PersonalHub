package com.gernalix.personalhub.core.database.capsules.sync

import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.work.*
import com.gernalix.personalhub.core.database.DatabaseGate
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One uploader; HTTP never holds the database writer gate. Pending rows survive retries/restarts. */
object DatasetteSync {
    private const val WORK = "personalhub-datasette"
    internal const val RECOVERY_WORK = "personalhub-datasette-recovery"
    private val uploads = ReentrantLock(true)
    // The short request-start gate makes OFF linearizable with starting a network request.
    private val requests = ReentrantLock(true)
    interface Scheduler {
        fun cancelLegacyWork(context: Context)
        fun enqueuePeriodicRecovery(context: Context)
        fun enqueueSync(context: Context)
        fun cancelSync(context: Context)
    }
    private object WorkManagerScheduler : Scheduler {
        override fun cancelLegacyWork(context: Context) {
            listOf("mtt-remote-sync-immediate", "mtt-remote-sync-periodic").forEach { WorkManager.getInstance(context).cancelUniqueWork(it) }
        }
        override fun enqueuePeriodicRecovery(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(RECOVERY_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DatasetteSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
        }
        override fun enqueueSync(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DatasetteSyncWorker>().setConstraints(network())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        override fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
    @Volatile private var scheduler: Scheduler = WorkManagerScheduler

    fun setSchedulerForTests(testScheduler: Scheduler) {
        scheduler = testScheduler
    }

    fun resetSchedulerForTests() {
        scheduler = WorkManagerScheduler
    }

    fun <T> pauseUploads(block: () -> T): T = uploads.withLock(block)
    private fun db(context: Context) = PersonalHubDatabase.get(context).openHelper.writableDatabase
    private fun statusPrefs(context: Context) = context.getSharedPreferences(
        "personalhub_sync_status" + DatabaseProfiles.preferenceSuffix(context),
        Context.MODE_PRIVATE,
    )
    fun status(context: Context): String = statusPrefs(context).getString("state", "idle") ?: "idle"
    fun pending(context: Context): Long = db(context).query("SELECT count(*) FROM hub_sync_pending").use { it.moveToFirst(); it.getLong(0) }

    fun setEnabled(context: Context, enabled: Boolean) {
        requests.withLock { DatasetteSettings.setEnabled(context, enabled) }
        if (enabled) request(context) else {
            scheduler.cancelSync(context.applicationContext)
            statusPrefs(context).edit().putString("state", "idle").apply()
        }
    }
    fun save(context: Context, url: String, database: String, table: String, token: String) {
        requests.withLock { DatasetteSettings.save(context, url, database, table, token) }
        request(context)
    }
    fun start(context: Context) {
        val app = context.applicationContext
        scheduler.cancelLegacyWork(app)
        scheduler.enqueuePeriodicRecovery(app)
        request(context)
    }
    fun request(context: Context) {
        if (!runCatching { DatasetteSettings.configuration(context).enabled }.getOrDefault(false)) return
        scheduler.enqueueSync(context.applicationContext)
    }
    fun checkForChanges(context: Context) {
        if (DatasetteSettings.configuration(context).enabled && (DatasetteSettings.needsFull(context) || pending(context) > 0)) request(context)
    }
    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    internal data class Item(val table: String, val key: String, val revision: Long, val row: JSONObject)

    private data class Snapshot(val table: String, val key: String, val revision: Long, val payload: Map<String, Any?>?)

    private fun snapshot(context: Context): List<Snapshot> = DatabaseGate.access {
        val db = db(context)
        db.beginTransaction()
        try {
            val tables = SyncJournal.tables(db).toSet()
            val primaryKeys = mutableMapOf<String, List<String>>()
            val pending = db.query("SELECT table_name,row_key,revision FROM hub_sync_pending ORDER BY table_name,row_key LIMIT 50").use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) }
            }
            var bytes = 0L
            val items = mutableListOf<Snapshot>()
            for ((table, key, revision) in pending) {
                require(table in tables)
                val columns = primaryKeys.getOrPut(table) { SyncJournal.primaryKeys(db, table) }
                val values = SyncJournal.keyValues(key)
                require(values.size == columns.size)
                val where = columns.joinToString(" AND ") { "`$it` IS ?" }
                // Cursor values (including owned BLOB byte arrays) are detached before releasing the gate.
                val payload = db.query("SELECT * FROM `$table` WHERE $where", values).use { c ->
                    if (!c.moveToFirst()) null else c.columnNames.mapIndexed { index, column -> column to when (c.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(index)
                        else -> c.getString(index)
                    } }.toMap()
                }
                val size = payload?.values?.sumOf { when (it) { is ByteArray -> it.size.toLong(); is String -> it.length * 2L; else -> 8L } } ?: 0
                if (items.isNotEmpty() && bytes + size > 512 * 1024) break
                bytes += size
                items.add(Snapshot(table, key, revision, payload))
            }
            db.setTransactionSuccessful()
            items
        } finally { db.endTransaction() }
    }

    private fun batch(context: Context, device: String, timestamp: Long, encodeBlob: (ByteArray) -> String): List<Item> {
        val captured = snapshot(context)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        var bytes = 0
        val items = mutableListOf<Item>()
        for ((table, key, revision, values) in captured) {
            // No writer gate during Base64, JSON construction/serialization, UUID or UTF-8 encoding.
            val payload = values?.let { JSONObject().also { json -> it.forEach { (column, value) ->
                json.put(column, when (value) {
                    null -> JSONObject.NULL
                    is ByteArray -> JSONObject().put("encoding", "base64").put("data", encodeBlob(value))
                    else -> value
                })
            } } }
            val deleted = when {
                values == null -> timestamp
                values["deleted_at_ms"] != null -> values["deleted_at_ms"] as Long
                table == "contacts" && values["deleted_at"] != null -> values["deleted_at"] as Long
                else -> null
            }
            val syncId = UUID.nameUUIDFromBytes("personalhub:$device:$table:$key".toByteArray(Charsets.UTF_8)).toString()
            val row = JSONObject().put("sync_id", syncId).put("device_id", device).put("entity_type", table).put("local_id", key)
                .put("payload_json", (payload ?: JSONObject()).toString()).put("updated_at_ms", timestamp).put("updated_at", Instant.ofEpochMilli(timestamp).toString())
                .put("deleted_at_ms", deleted ?: JSONObject.NULL).put("deleted_at", deleted?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
                .put("source_app_version", version)
            val rowBytes = row.toString().toByteArray(Charsets.UTF_8).size
            if (items.isNotEmpty() && bytes + rowBytes > 512 * 1024) break
            bytes += rowBytes
            items.add(Item(table, key, revision, row))
        }
        // Record only prepared identities, before any HTTP. Import still serializes with uploads.
        if (items.isEmpty()) return items
        DatabaseGate.access {
            val db = db(context)
            db.beginTransaction()
            try {
                items.forEach { db.execSQL("INSERT OR IGNORE INTO hub_sync_known VALUES (?,?)", arrayOf(it.table, it.key)) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return items
    }

    fun run(context: Context, stopped: () -> Boolean = { false }, send: (DatasetteConfiguration, String, List<JSONObject>) -> Boolean = DatasetteClient::upsert, encodeBlob: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }): Boolean = uploads.withLock {
        try {
            if (!DatasetteSettings.configuration(context).enabled || stopped()) return true
            if (DatasetteSettings.needsFull(context)) requests.withLock {
                DatabaseGate.access { SyncJournal.enqueueAll(db(context)) }
                DatasetteSettings.fullQueued(context)
            }
            val device = DatasetteSettings.deviceId(context)
            statusPrefs(context).edit().putString("state", "sending").apply()
            // Bound each run to leave time for cancellation and WorkManager backoff.
            val deadline = android.os.SystemClock.elapsedRealtime() + 240_000
            repeat(10_000) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) return false
                if (stopped() || !DatasetteSettings.configuration(context).enabled) return true
                val items = batch(context, device, DatasetteSettings.nextTimestamp(context), encodeBlob)
                if (items.isEmpty()) {
                    statusPrefs(context).edit().putString("state", "complete").apply()
                    return true
                }
                val sent = requests.withLock {
                    val config = DatasetteSettings.configuration(context)
                    if (!config.enabled || stopped()) return true
                    // OFF waits for this finite request to finish; no later request can start.
                    send(config, DatasetteSettings.token(context), items.map { it.row })
                }
                if (!sent) {
                    statusPrefs(context).edit().putString("state", "retry").apply()
                    return false
                }
                DatabaseGate.access {
                    val db = db(context)
                    db.beginTransaction()
                    try {
                        items.forEach { db.execSQL("DELETE FROM hub_sync_pending WHERE table_name=? AND row_key=? AND revision=?", arrayOf(it.table, it.key, it.revision)) }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            }
            false
        } catch (_: Exception) {
            statusPrefs(context).edit().putString("state", "retry").apply()
            false
        }
    }
}

class DatasetteSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = if (DatasetteSync.run(applicationContext, { isStopped })) Result.success() else Result.retry()
}
