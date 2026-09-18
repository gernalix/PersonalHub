package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.DatabaseGate
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.util.UUID

data class GitRevision(
    val sha: String,
    val message: String,
    val committedAt: Long,
)

data class GitStateDiff(
    val table: String,
    val beforeRows: Long,
    val afterRows: Long,
    val changed: Boolean,
)

data class GitMilestone(val name: String, val sha: String)

data class GitHistoryStats(
    val byAuthor: List<GitHistoryCount>,
    val byTable: List<GitHistoryCount>,
)

data class GitHistoryDetail(
    val item: GitHistoryItem,
    val before: String?,
    val after: String?,
)

/**
 * User-facing history primitives: global Time Machine, blame, archaeology, granular revert,
 * milestones and semantic revision diffs. Git is the durable history; SQLite holds only an index.
 */
object GitHistory {
    fun recent(
        context: Context,
        limit: Int = 200,
        author: String? = null,
        table: String? = null,
        rowKey: String? = null,
    ): List<GitHistoryItem> = DatabaseGate.access {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        GitHistoryStore.install(db)
        GitHistoryStore.recent(db, limit, author, table, rowKey)
    }

    fun blame(
        context: Context,
        table: String,
        rowKey: String,
        column: String? = null,
        limit: Int = 100,
    ): List<GitHistoryItem> =
        recent(context, limit, table = table, rowKey = rowKey).filter { item ->
            column == null || item.changedColumns.split(',').contains(column)
        }

    fun detail(context: Context, eventId: String): GitHistoryDetail {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        GitHistoryStore.install(db)
        val item = GitHistoryStore.find(db, eventId)
            ?: error("History event is not indexed on this device")
        val event = loadEvent(context.applicationContext, item)
        return GitHistoryDetail(
            item = item,
            before = event.optJSONObject("before")?.toString(2),
            after = event.optJSONObject("after")?.toString(2),
        )
    }

    fun stats(context: Context): GitHistoryStats = DatabaseGate.access {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        GitHistoryStore.install(db)
        GitHistoryStats(
            byAuthor = GitHistoryStore.countsByAuthor(db),
            byTable = GitHistoryStore.countsByTable(db),
        )
    }

    fun revisions(context: Context, limit: Int = 100): List<GitRevision> =
        remote(context).revisions(limit)

    /**
     * Timer compatibility bridge while its former local Time Machine is retired.
     * Reads the canonical snapshot row from the newest Git state committed at/before targetMs.
     */
    fun readTimerSnapshotAsOf(context: Context, targetMs: Long): String? {
        val revision = remote(context).revisionAtOrBefore(targetMs) ?: return null
        val transport = transport(context)
        val manifest = JSONObject(
            String(transport.readFile(GIT_STATE_MANIFEST, revision.sha), Charsets.UTF_8),
        )
        val table = manifestTables(manifest)["snapshot"] ?: return null
        val shards = table.optJSONArray("shards")
        if (shards == null) {
            val bytes = transport.readFile(table.getString("path"), revision.sha)
            return snapshotJsonFromRows(bytes)
        }
        for (i in 0 until shards.length()) {
            val shard = shards.getJSONObject(i)
            val bytes = transport.readFile(shard.getString("path"), revision.sha)
            snapshotJsonFromRows(bytes)?.let { return it }
        }
        return null
    }

    private fun snapshotJsonFromRows(bytes: ByteArray): String? =
        String(bytes, Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .map(::JSONObject)
            .firstOrNull { it.optLong("id", -1L) == 1L }
            ?.optString("json")

    fun milestones(context: Context): List<GitMilestone> = remote(context).milestones()

    fun createMilestone(context: Context, name: String): GitMilestone =
        remote(context).createMilestone(name)

    fun compare(context: Context, before: String, after: String): List<GitStateDiff> {
        val transport = transport(context)
        val left = JSONObject(String(transport.readFile(GIT_STATE_MANIFEST, before), Charsets.UTF_8))
        val right = JSONObject(String(transport.readFile(GIT_STATE_MANIFEST, after), Charsets.UTF_8))
        val leftTables = manifestTables(left)
        val rightTables = manifestTables(right)
        return (leftTables.keys + rightTables.keys).sorted().map { table ->
            val a = leftTables[table]
            val b = rightTables[table]
            GitStateDiff(
                table = table,
                beforeRows = a?.optLong("rows", 0L) ?: 0L,
                afterRows = b?.optLong("rows", 0L) ?: 0L,
                changed = fingerprint(a) != fingerprint(b),
            )
        }
    }

    fun revertEvent(context: Context, eventId: String) {
        val app = context.applicationContext
        val db = PersonalHubDatabase.get(app).openHelper.writableDatabase
        GitHistoryStore.install(db)
        val selected = GitHistoryStore.find(db, eventId)
            ?: error("History event is not indexed on this device")
        val items = selected.groupId
            ?.let { GitHistoryStore.byGroup(db, it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(selected)
        require(items.all { it.revertedBy == null }) {
            "At least one part of this logical edit was already reverted"
        }
        val payloads = items.associateWith { loadEvent(app, it) }
        val group = "revert:" + eventId + ":" + UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            GitDataTracking.setEditContext(
                db = db,
                author = "user",
                source = "history_revert",
                reason = if (items.size == 1) {
                    "Revert history event $eventId"
                } else {
                    "Revert logical edit " + requireNotNull(selected.groupId)
                },
                groupId = group,
            )
            // Undo multi-row logical edits in reverse order to preserve dependency direction.
            items.sortedWith(
                compareByDescending<GitHistoryItem> { it.occurredAt }.thenByDescending { it.id },
            ).forEach { item ->
                applyInverse(app, db, item, requireNotNull(payloads[item]))
            }
            db.query("PRAGMA foreign_key_check").use {
                require(!it.moveToFirst()) { "Revert would break database relationships" }
            }
            items.forEach { GitHistoryStore.markReverted(db, it.id, group) }
            db.setTransactionSuccessful()
        } finally {
            GitDataTracking.clearEditContext(db)
            db.endTransaction()
        }
    }

    /**
     * Returns history events in a time window. This powers "what happened that day?" and
     * data-debugging/bisect workflows without scanning Git on every query.
     */
    fun eventsBetween(
        context: Context,
        fromMs: Long,
        toMs: Long,
        table: String? = null,
        limit: Int = 1000,
    ): List<GitHistoryItem> = DatabaseGate.access {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        GitHistoryStore.install(db)
        GitHistoryStore.between(db, fromMs, toMs, table, limit)
    }

    fun suspectChanges(
        context: Context,
        table: String,
        knownGoodMs: Long,
        knownBadMs: Long,
    ): List<GitHistoryItem> {
        require(knownGoodMs <= knownBadMs)
        return eventsBetween(context, knownGoodMs, knownBadMs, table, 5000)
    }

    /**
     * Rebuilds the disposable index from immutable history files. This is intentionally explicit:
     * normal startup never scans Git history.
     */
    fun rebuildIndex(context: Context) {
        val app = context.applicationContext
        val remote = remote(app)
        val head = transport(app).remoteHead()
        val files = remote.historyPaths(head.commitSha)
        val db = PersonalHubDatabase.get(app).openHelper.writableDatabase
        GitHistoryStore.install(db)
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM " + GitHistoryStore.TABLE)
            files.forEach { path ->
                val bytes = transport(app).readFile(path, head.commitSha)
                transport(app).readFileOrNull(path + ".sig.json", head.commitSha)?.let { signature ->
                    require(GitDataSigner.verify(bytes, signature)) {
                        "Git history signature verification failed: $path"
                    }
                }
                String(bytes, Charsets.UTF_8).lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val event = JSONObject(line)
                        val changed = event.optJSONArray("changed_columns") ?: JSONArray()
                        val changedColumns = buildList {
                            for (i in 0 until changed.length()) add(changed.getString(i))
                        }.joinToString(",")
                        db.execSQL(
                            "INSERT OR REPLACE INTO " + GitHistoryStore.TABLE + "(" +
                                "id,occurred_at,author,source,reason,group_id,table_name,operation,row_key," +
                                "changed_columns,history_path,commit_sha,reverted_by" +
                                ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
                            arrayOf(
                                event.getString("event_id"),
                                event.getLong("timestamp_ms"),
                                event.optString("author", "unknown"),
                                event.optString("source", "unknown"),
                                event.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                                event.optString("group_id").takeIf { it.isNotBlank() && it != "null" },
                                event.getString("table"),
                                event.getString("operation").uppercase(),
                                event.getString("row_key"),
                                changedColumns,
                                path,
                                head.commitSha,
                            ),
                        )
                    }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun createProposalBranch(context: Context, name: String): String =
        remote(context).createProposalBranch(name)

    fun createProposalPullRequest(
        context: Context,
        branch: String,
        title: String,
        body: String,
    ): String = remote(context).createPullRequest(branch, title, body)

    private fun loadEvent(context: Context, item: GitHistoryItem): JSONObject {
        val bytes = transport(context).readFile(item.historyPath, item.commitSha)
        return String(bytes, Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .map(::JSONObject)
            .firstOrNull { it.getString("event_id") == item.id }
            ?: error("History event payload is missing")
    }

    private fun applyInverse(
        context: Context,
        db: SupportSQLiteDatabase,
        item: GitHistoryItem,
        event: JSONObject,
    ) {
        val table = item.table
        GitDataFormat.requireSafeIdentifier(table)
        require(table !in SyncJournal.excluded && table !in GitDataTracking.operationalTables)
        val keys = SyncJournal.primaryKeys(db, table)
        require(keys.isNotEmpty())
        val keyValues = SyncJournal.keyValues(item.rowKey)
        require(keyValues.size == keys.size)
        val where = keys.joinToString(" AND ") { "`$it`=?" }
        val before = event.optJSONObject("before")
        val after = event.optJSONObject("after")
        val changed = event.optJSONArray("changed_columns") ?: JSONArray()
        val changedColumns = buildList {
            for (i in 0 until changed.length()) add(changed.getString(i))
        }

        when (item.operation.uppercase()) {
            "INSERT" -> {
                val current = readRow(db, table, where, keyValues)
                    ?: error("Cannot revert insert: current row is missing")
                require(matches(context, item, after, current, changedColumns)) {
                    "Cannot revert insert: row changed later"
                }
                require(db.delete(table, where, keyValues) == 1)
            }
            "DELETE" -> {
                require(readRow(db, table, where, keyValues) == null) {
                    "Cannot restore delete: primary key is already in use"
                }
                val source = requireNotNull(before) { "Deleted row payload is missing" }
                val values = contentValues(context, item, source)
                require(db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values) != -1L)
            }
            "UPDATE" -> {
                val current = readRow(db, table, where, keyValues)
                    ?: error("Cannot revert update: current row is missing")
                require(matches(context, item, after, current, changedColumns)) {
                    "Cannot revert update: changed fields were edited later"
                }
                val source = requireNotNull(before) { "Previous row payload is missing" }
                val values = ContentValues()
                changedColumns.forEach { column ->
                    putResolved(context, item, values, column, source.get(column))
                }
                require(db.update(table, SQLiteDatabase.CONFLICT_ABORT, values, where, keyValues) == 1)
            }
            else -> error("Unsupported history operation")
        }
    }

    private fun readRow(
        db: SupportSQLiteDatabase,
        table: String,
        where: String,
        args: Array<Any?>,
    ): Map<String, Any?>? =
        db.query("SELECT * FROM `$table` WHERE $where LIMIT 2", args).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val row = buildMap<String, Any?> {
                for (i in 0 until cursor.columnCount) put(cursor.getColumnName(i), cursorValue(cursor, i))
            }
            require(!cursor.moveToNext()) { "Primary key is not unique" }
            row
        }

    private fun matches(
        context: Context,
        item: GitHistoryItem,
        expected: JSONObject?,
        current: Map<String, Any?>,
        columns: List<String>,
    ): Boolean {
        if (expected == null) return false
        return columns.all { column ->
            rawEquals(resolveHistoryValue(context, item, expected.get(column)), current[column])
        }
    }

    private fun contentValues(
        context: Context,
        item: GitHistoryItem,
        source: JSONObject,
    ): ContentValues =
        ContentValues().also { values ->
            source.keys().forEach { column ->
                putResolved(context, item, values, column, source.get(column))
            }
        }

    private fun putResolved(
        context: Context,
        item: GitHistoryItem,
        values: ContentValues,
        column: String,
        encoded: Any?,
    ) {
        val value = resolveHistoryValue(context, item, encoded)
        when (value) {
            null, JSONObject.NULL -> values.putNull(column)
            is ByteArray -> values.put(column, value)
            is String -> values.put(column, value)
            is Long -> values.put(column, value)
            is Int -> values.put(column, value)
            is Double -> values.put(column, value)
            is Float -> values.put(column, value)
            is Number -> values.put(column, value.toString())
            else -> error("Unsupported historical value")
        }
    }

    private fun resolveHistoryValue(
        context: Context,
        item: GitHistoryItem,
        encoded: Any?,
    ): Any? {
        if (encoded == null || encoded === JSONObject.NULL) return null
        if (encoded is JSONObject && encoded.has("\$object")) {
            val bytes = transport(context).readFile(encoded.getString("path"), item.commitSha)
            require(GitDataFormat.sha256(bytes) == encoded.getString("\$object")) {
                "History object hash mismatch"
            }
            return bytes
        }
        return GitDataFormat.decodeJsonValue(encoded)
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any? =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
            else -> error("Unsupported SQLite value")
        }

    private fun rawEquals(left: Any?, right: Any?): Boolean {
        if (left is ByteArray && right is ByteArray) return left.contentEquals(right)
        if (left is Number && right is Number) return left.toString().toBigDecimal()
            .compareTo(right.toString().toBigDecimal()) == 0
        return left == right
    }

    private fun manifestTables(manifest: JSONObject): Map<String, JSONObject> {
        val tables = manifest.getJSONArray("tables")
        return buildMap {
            for (i in 0 until tables.length()) {
                val table = tables.getJSONObject(i)
                put(table.getString("name"), table)
            }
        }
    }

    private fun fingerprint(entry: JSONObject?): String {
        if (entry == null) return ""
        val shards = entry.optJSONArray("shards")
        if (shards == null) return entry.optString("sha256")
        return buildString {
            for (i in 0 until shards.length()) append(shards.getJSONObject(i).getString("sha256"))
        }
    }

    private fun transport(context: Context): GitHubDataTransport {
        val config = GitDataSettings.configuration(context)
        require(config.enabled && config.configured) { "Git data sync is disabled or incomplete" }
        return GitHubDataTransport(
            repository = requireNotNull(config.repository),
            token = GitDataSettings.token(context),
        )
    }

    private fun remote(context: Context): GitHistoryRemote {
        val config = GitDataSettings.configuration(context)
        require(config.enabled && config.configured) { "Git data sync is disabled or incomplete" }
        return GitHistoryRemote(
            repository = requireNotNull(config.repository),
            token = GitDataSettings.token(context),
        )
    }
}

private class GitHistoryRemote(
    private val repository: GitRepository,
    private val token: String,
) {
    private val api = "https://api.github.com/repos/" + repository.owner + "/" + repository.name

    fun revisionAtOrBefore(targetMs: Long): GitRevision? {
        val until = encode(Instant.ofEpochMilli(targetMs).toString())
        val values = JSONArray(
            String(
                request(
                    "GET",
                    api + "/commits?path=" + encode("state/manifest.json") +
                        "&until=" + until + "&per_page=1",
                ),
                Charsets.UTF_8,
            ),
        )
        if (values.length() == 0) return null
        val item = values.getJSONObject(0)
        val commit = item.getJSONObject("commit")
        val author = commit.optJSONObject("author")
        return GitRevision(
            sha = item.getString("sha"),
            message = commit.optString("message").lineSequence().firstOrNull().orEmpty(),
            committedAt = author?.optString("date")?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
        )
    }

    fun revisions(limit: Int): List<GitRevision> {
        require(limit in 1..100)
        val values = JSONArray(
            String(
                request(
                    "GET",
                    api + "/commits?path=" + encode("state/manifest.json") + "&per_page=" + limit,
                ),
                Charsets.UTF_8,
            ),
        )
        return buildList {
            for (i in 0 until values.length()) {
                val item = values.getJSONObject(i)
                val commit = item.getJSONObject("commit")
                val author = commit.optJSONObject("author")
                add(
                    GitRevision(
                        sha = item.getString("sha"),
                        message = commit.optString("message").lineSequence().firstOrNull().orEmpty(),
                        committedAt = author?.optString("date")?.takeIf { it.isNotBlank() }
                            ?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
                    ),
                )
            }
        }
    }

    fun milestones(): List<GitMilestone> {
        val values = JSONArray(String(request("GET", api + "/tags?per_page=100"), Charsets.UTF_8))
        return buildList {
            for (i in 0 until values.length()) {
                val item = values.getJSONObject(i)
                if (item.getString("name").startsWith("ph-")) {
                    add(GitMilestone(item.getString("name"), item.getJSONObject("commit").getString("sha")))
                }
            }
        }
    }

    fun createMilestone(name: String): GitMilestone {
        val slug = name.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .take(60)
        require(slug.isNotBlank()) { "Milestone name is empty" }
        val transport = GitHubDataTransport(repository, token)
        val head = transport.remoteHead()
        val tag = "ph-" + java.time.LocalDate.now().toString() + "-" + slug
        json(
            "POST",
            api + "/git/refs",
            JSONObject().put("ref", "refs/tags/" + tag).put("sha", head.commitSha),
        )
        return GitMilestone(tag, head.commitSha)
    }

    fun historyPaths(commitSha: String): List<String> {
        val commit = json("GET", api + "/git/commits/" + encode(commitSha))
        val treeSha = commit.getJSONObject("tree").getString("sha")
        val tree = json("GET", api + "/git/trees/" + treeSha + "?recursive=1")
        val items = tree.getJSONArray("tree")
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val path = item.optString("path")
                if (item.optString("type") == "blob" &&
                    path.startsWith("history/") && path.endsWith(".jsonl")
                ) add(path)
            }
        }.sorted()
    }

    fun createProposalBranch(name: String): String {
        val slug = name.trim().lowercase()
            .replace(Regex("[^a-z0-9._/-]+"), "-")
            .trim('-', '/')
        require(slug.isNotBlank()) { "Proposal branch name is empty" }
        val branch = if (slug.startsWith("data/")) slug else "data/" + slug
        val head = GitHubDataTransport(repository, token).remoteHead()
        json(
            "POST",
            api + "/git/refs",
            JSONObject().put("ref", "refs/heads/" + branch).put("sha", head.commitSha),
        )
        return branch
    }

    fun createPullRequest(branch: String, title: String, body: String): String {
        val head = GitHubDataTransport(repository, token).remoteHead()
        val result = json(
            "POST",
            api + "/pulls",
            JSONObject()
                .put("title", title.trim())
                .put("head", branch)
                .put("base", head.branch)
                .put("body", body),
        )
        return result.getString("html_url")
    }

    private fun json(method: String, url: String, body: JSONObject? = null): JSONObject =
        JSONObject(
            String(
                request(
                    method,
                    url,
                    body?.toString()?.toByteArray(Charsets.UTF_8),
                ),
                Charsets.UTF_8,
            ),
        )

    private fun request(method: String, url: String, body: ByteArray? = null): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "PersonalHub-GitHistory")
        connection.setRequestProperty("Authorization", "Bearer " + token)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body) }
        }
        return try {
            val code = connection.responseCode
            require(code in 200..299) {
                val error = connection.errorStream?.use { input ->
                    val output = ByteArrayOutputStream()
                    input.copyTo(output)
                    output.toString(Charsets.UTF_8.name()).take(1000)
                }.orEmpty()
                "GitHub history request failed (" + code + "): " + error
            }
            if (code == HttpURLConnection.HTTP_NO_CONTENT) ByteArray(0)
            else connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
