package com.gernalix.personalhub.core.database.capsules.sync

import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.work.*
import com.gernalix.personalhub.core.database.DatabaseGate
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
    private val uploads = ReentrantLock(true)
    // The short request-start gate makes OFF linearizable with starting a network request.
    private val requests = ReentrantLock(true)
    fun <T> pauseUploads(block: () -> T): T = uploads.withLock(block)
    private fun db(context: Context) = PersonalHubDatabase.get(context).openHelper.writableDatabase
    private fun statusPrefs(context: Context) = context.getSharedPreferences("personalhub_sync_status", Context.MODE_PRIVATE)
    fun status(context: Context): String = statusPrefs(context).getString("state", "idle") ?: "idle"
    fun pending(context: Context): Long = db(context).query("SELECT count(*) FROM hub_sync_pending").use { it.moveToFirst(); it.getLong(0) }

    fun setEnabled(context: Context, enabled: Boolean) {
        requests.withLock { DatasetteSettings.setEnabled(context, enabled) }
        if (enabled) request(context) else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            statusPrefs(context).edit().putString("state", "idle").apply()
        }
    }
    fun save(context: Context, url: String, database: String, table: String, token: String) {
        requests.withLock { DatasetteSettings.save(context, url, database, table, token) }
        request(context)
    }
    fun start(context: Context) {
        listOf("mtt-remote-sync-immediate", "mtt-remote-sync-periodic").forEach { WorkManager.getInstance(context).cancelUniqueWork(it) }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("personalhub-datasette-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DatasetteSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
        request(context)
    }
    fun request(context: Context) {
        if (!runCatching { DatasetteSettings.configuration(context).enabled }.getOrDefault(false)) return
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DatasetteSyncWorker>().setConstraints(network())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun checkForChanges(context: Context) {
        if (DatasetteSettings.configuration(context).enabled && (DatasetteSettings.needsFull(context) || pending(context) > 0)) request(context)
    }
    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    internal data class Item(val table: String, val key: String, val revision: Long, val row: JSONObject)

    private fun batch(context: Context, device: String, timestamp: Long): List<Item> = DatabaseGate.access {
        val db = db(context)
        db.beginTransaction()
        try {
        val tables = SyncJournal.tables(db).toSet()
        val primaryKeys = mutableMapOf<String, List<String>>()
        val pending = db.query("SELECT table_name,row_key,revision FROM hub_sync_pending ORDER BY table_name,row_key LIMIT 50").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) }
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        var bytes = 0
        val items = mutableListOf<Item>()
        for ((table, key, revision) in pending) {
            require(table in tables)
            val columns = primaryKeys.getOrPut(table) { SyncJournal.primaryKeys(db, table) }
            val values = SyncJournal.keyValues(key)
            require(values.size == columns.size)
            val where = columns.joinToString(" AND ") { "`$it` IS ?" }
            val payload = db.query("SELECT * FROM `$table` WHERE $where", values).use { c ->
                if (!c.moveToFirst()) null else JSONObject().also { json ->
                    c.columnNames.forEachIndexed { index, column -> json.put(column, when (c.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
                        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("encoding", "base64").put("data", Base64.encodeToString(c.getBlob(index), Base64.NO_WRAP))
                        else -> c.getString(index)
                    }) }
                }
            }
            val deleted = when {
                payload == null -> timestamp
                !payload.isNull("deleted_at_ms") -> payload.getLong("deleted_at_ms")
                table == "contacts" && !payload.isNull("deleted_at") -> payload.getLong("deleted_at")
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
            // Record before HTTP: an ambiguous response must still be reconciled after an import.
            db.execSQL("INSERT OR IGNORE INTO hub_sync_known VALUES (?,?)", arrayOf(table, key))
            items.add(Item(table, key, revision, row))
        }
        db.setTransactionSuccessful()
        items
        } finally { db.endTransaction() }
    }

    fun run(context: Context, stopped: () -> Boolean = { false }, send: (DatasetteConfiguration, String, List<JSONObject>) -> Boolean = DatasetteClient::upsert): Boolean = uploads.withLock {
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
                val items = batch(context, device, DatasetteSettings.nextTimestamp(context))
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
