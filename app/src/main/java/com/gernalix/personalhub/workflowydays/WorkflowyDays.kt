package com.gernalix.personalhub.workflowydays

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/** A materialized Workflowy Calendar day. The deep link is deterministic from the UUID. */
data class WorkflowyDay(val date: LocalDate, val nodeId: String) {
    val deepLink: String get() = "https://workflowy.com/#/${nodeId.takeLast(12)}"
}

/**
 * App-private store deliberately kept outside PersonalHubDatabase.
 *
 * Downloaded Workflowy metadata must not enter the generic Datasette upload journal and be
 * echoed back to the server. This tiny DB is still internal app data, but has one owner and one
 * direction: remote Workflowy index -> PersonalHub.
 */
object WorkflowyDaysStore {
    private const val DB_NAME = "workflowy_days.db"
    private const val DB_VERSION = 1

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE workflowy_days (
                    date TEXT NOT NULL,
                    node_id TEXT NOT NULL,
                    PRIMARY KEY(date, node_id)
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile private var helper: Helper? = null
    private fun db(context: Context): SQLiteDatabase {
        val existing = helper
        if (existing != null) return existing.writableDatabase
        return synchronized(this) {
            (helper ?: Helper(context.applicationContext).also { helper = it }).writableDatabase
        }
    }

    fun replaceAll(context: Context, days: List<WorkflowyDay>) {
        val database = db(context)
        database.beginTransaction()
        try {
            database.delete("workflowy_days", null, null)
            for (day in days.distinctBy { it.date to it.nodeId }) {
                val values = ContentValues().apply {
                    put("date", day.date.toString())
                    put("node_id", day.nodeId)
                }
                check(database.insertOrThrow("workflowy_days", null, values) != -1L)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun findAll(context: Context, date: LocalDate): List<WorkflowyDay> =
        db(context).query(
            "workflowy_days",
            arrayOf("date", "node_id"),
            "date=?",
            arrayOf(date.toString()),
            null,
            null,
            "node_id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(WorkflowyDay(LocalDate.parse(cursor.getString(0)), cursor.getString(1)))
                }
            }
        }

    /** Returns a day only when its Workflowy target is unambiguous. */
    fun find(context: Context, date: LocalDate): WorkflowyDay? =
        findAll(context, date).singleOrNull()

    fun between(context: Context, from: LocalDate, to: LocalDate): List<WorkflowyDay> {
        require(!to.isBefore(from))
        return db(context).query(
            "workflowy_days",
            arrayOf("date", "node_id"),
            "date>=? AND date<=?",
            arrayOf(from.toString(), to.toString()),
            null,
            null,
            "date ASC, node_id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(WorkflowyDay(LocalDate.parse(cursor.getString(0)), cursor.getString(1)))
                }
            }
        }
    }
}

object WorkflowyDaysFeed {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Accepts the compact VM feed or Datasette `_shape=array` rows from workflowy_days. */
    fun parse(raw: String): List<WorkflowyDay> {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Empty Workflowy-days feed" }
        val rows = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            val root = JSONObject(text)
            require(root.getInt("schema_version") == 1) { "Unsupported Workflowy-days schema" }
            root.getJSONArray("workflowy_days")
        }

        val unique = linkedMapOf<Pair<LocalDate, String>, WorkflowyDay>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val parsedDate = try {
                LocalDate.parse(row.getString("date"))
            } catch (error: DateTimeParseException) {
                throw IllegalArgumentException("Invalid Workflowy day date at index $index", error)
            }
            val nodeId = row.getString("node_id")
            require(uuid.matches(nodeId)) { "Invalid Workflowy node UUID at index $index" }
            unique[parsedDate to nodeId] = WorkflowyDay(parsedDate, nodeId)
        }
        return unique.values.sortedWith(compareBy<WorkflowyDay> { it.date }.thenBy { it.nodeId })
    }
}

/** One-way, optional downloader. It is intentionally independent from DatasetteSync. */
object WorkflowyDaysSync {
    private const val PREFS = "workflowy_days_sync"
    private const val KEY_URL = "feed_url"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ETAG = "etag"
    private const val KEY_STATUS = "status"
    private const val PERIODIC_WORK = "personalhub-workflowy-days-periodic"
    private const val NOW_WORK = "personalhub-workflowy-days-now"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configure(context: Context, feedUrl: String, enabled: Boolean = true) {
        require(feedUrl.startsWith("https://")) { "Workflowy-days feed must use HTTPS" }
        prefs(context).edit().putString(KEY_URL, feedUrl).putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            ensureScheduled(context)
            requestNow(context)
        } else {
            cancel(context)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val preferences = prefs(context)
        if (enabled) require(!preferences.getString(KEY_URL, null).isNullOrBlank()) { "Workflowy-days feed URL is not configured" }
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            ensureScheduled(context)
            requestNow(context)
        } else {
            cancel(context)
        }
    }

    fun status(context: Context): String = prefs(context).getString(KEY_STATUS, "idle") ?: "idle"

    fun deepLinksFor(context: Context, date: LocalDate): List<String> =
        WorkflowyDaysStore.findAll(context, date).map { it.deepLink }

    /** Null means either no node for that date or more than one valid Workflowy Calendar node. */
    fun deepLinkFor(context: Context, date: LocalDate): String? =
        WorkflowyDaysStore.find(context, date)?.deepLink

    fun open(context: Context, date: LocalDate): Boolean {
        val link = deepLinkFor(context, date) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** Safe to call repeatedly; no work is scheduled until a feed URL has been configured. */
    fun ensureScheduled(context: Context) {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_ENABLED, false) || preferences.getString(KEY_URL, null).isNullOrBlank()) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<WorkflowyDaysWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun requestNow(context: Context) {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_ENABLED, false) || preferences.getString(KEY_URL, null).isNullOrBlank()) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            NOW_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WorkflowyDaysWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NOW_WORK)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK)
        prefs(context).edit().putString(KEY_STATUS, "idle").apply()
    }

    internal fun run(context: Context): Boolean {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_ENABLED, false)) return true
        val url = preferences.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return true
        preferences.edit().putString(KEY_STATUS, "downloading").apply()

        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            preferences.getString(KEY_ETAG, null)?.let { connection.setRequestProperty("If-None-Match", it) }

            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    preferences.edit().putString(KEY_STATUS, "complete").apply()
                    true
                }
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val days = WorkflowyDaysFeed.parse(body)
                    WorkflowyDaysStore.replaceAll(context, days)
                    val edit = preferences.edit().putString(KEY_STATUS, "complete")
                    connection.getHeaderField("ETag")?.let { edit.putString(KEY_ETAG, it) }
                    edit.apply()
                    true
                }
                else -> {
                    preferences.edit().putString(KEY_STATUS, "retry").apply()
                    false
                }
            }
        } catch (_: Exception) {
            preferences.edit().putString(KEY_STATUS, "retry").apply()
            false
        } finally {
            connection?.disconnect()
        }
    }
}

class WorkflowyDaysWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result =
        if (WorkflowyDaysSync.run(applicationContext)) Result.success() else Result.retry()
}
