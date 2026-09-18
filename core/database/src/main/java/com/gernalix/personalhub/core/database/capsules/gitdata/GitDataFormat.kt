package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.DatabaseGate
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

internal const val GIT_STATE_MANIFEST = "state/manifest.json"
internal const val GIT_CONTROL_MANIFEST = "control/manifest.json"

internal data class GitExportBundle(
    val files: Map<String, ByteArray>,
    val manifest: JSONObject,
    val pending: List<Pair<String, Long>>,
    val events: List<GitEditEvent>,
    val eventMeta: List<GitHistoryCommitMeta>,
    val generation: Long,
)

internal object GitDataFormat {
    private val excludedTables = SyncJournal.excluded + GitDataTracking.TABLE

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun exportPending(context: Context): GitExportBundle? {
        var pending: List<Pair<String, Long>> = emptyList()
        var events: List<GitEditEvent> = emptyList()
        var snapshot: File? = null
        DatabaseGate.access {
            val live = PersonalHubDatabase.get(context).openHelper.writableDatabase
            GitHistoryStore.install(live)
            pending = GitDataTracking.pending(live)
            events = GitDataTracking.events(live)
            if (pending.isEmpty() && events.isEmpty() &&
                GitDataSettings.readCachedStateManifest(context) != null
            ) return@access
            snapshot = DatabaseVault.backupCurrent(context)
        }
        val source = snapshot ?: return null
        try {
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val generation = generation(db)
                val allTables = tableNames(db)
                val cached = GitDataSettings.readCachedStateManifest(context)
                val cachedSchema = cached?.optInt("schema_version", -1) ?: -1
                val cachedEntries = cachedEntries(cached)
                val cachedTables = cachedEntries.keys
                val full = cachedSchema != PersonalHubDatabase.SCHEMA_VERSION ||
                    cachedTables != allTables.toSet()

                val changed = if (full) allTables.toSet() else pending.map { it.first }.toSet()
                if (changed.isEmpty()) return null

                val files = linkedMapOf<String, ByteArray>()
                val entries = if (full) linkedMapOf() else cachedEntries.toMutableMap()
                for (table in changed.sorted()) {
                    require(table in allTables) { "Unknown Git sync table: $table" }
                    val snapshot = exportTableShards(db, table, files)
                    val previous = cachedEntries[table]
                    val previousHashes = previous?.optJSONArray("shards")?.let { shards ->
                        buildMap {
                            for (i in 0 until shards.length()) {
                                val shard = shards.getJSONObject(i)
                                put(shard.getString("path"), shard.getString("sha256"))
                            }
                        }
                    }.orEmpty()
                    snapshot.files.forEach { (path, bytes) ->
                        if (previousHashes[path] != sha256(bytes)) files[path] = bytes
                    }
                    entries[table] = snapshot.entry
                }

                val manifest = JSONObject()
                    .put("format_version", 2)
                    .put("schema_version", PersonalHubDatabase.SCHEMA_VERSION)
                    .put("app_version", appVersion(context))
                    .put("generation", generation)
                    .put("created_at_ms", System.currentTimeMillis())
                    .put(
                        "tables",
                        JSONArray().apply {
                            entries.toSortedMap().values.forEach(::put)
                        },
                    )
                val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
                files[GIT_STATE_MANIFEST] = manifestBytes
                if (full) {
                    files["state/schema.json"] = context.assets.open(
                        "com.gernalix.personalhub.core.database.PersonalHubDatabase/" +
                            "${PersonalHubDatabase.SCHEMA_VERSION}.json",
                    ).use { it.readBytes() }
                }
                val change = JSONObject()
                    .put("format_version", 1)
                    .put("generation", generation)
                    .put("created_at_ms", System.currentTimeMillis())
                    .put("tables", JSONArray(changed.sorted()))
                files[
                    "changes/${System.currentTimeMillis()}-g$generation.json"
                ] = change.toString(2).toByteArray(Charsets.UTF_8)
                val history = encodeHistory(events, files)
                history?.let { (path, bytes) ->
                    files[path] = bytes
                    files[path + ".sig.json"] = GitDataSigner.signatureDocument(bytes)
                }
                val historyPath = history?.first.orEmpty()
                val meta = events.map { event ->
                    GitHistoryCommitMeta(
                        id = event.id,
                        historyPath = historyPath,
                        changedColumns = changedColumns(event),
                    )
                }
                return GitExportBundle(files, manifest, pending, events, meta, generation)
            }
        } finally {
            source.delete()
            listOf("-wal", "-shm", "-journal").forEach { File(source.path + it).delete() }
        }
    }

    fun acknowledge(context: Context, bundle: GitExportBundle, revision: String) {
        DatabaseGate.access {
            val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
            GitHistoryStore.install(db)
            db.beginTransaction()
            try {
                GitHistoryStore.indexCommitted(db, bundle.events, bundle.eventMeta, revision)
                GitDataTracking.acknowledge(db, bundle.pending, bundle.events.map { it.id })
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        GitDataSettings.writeCachedStateManifest(context, bundle.manifest)
    }

    private fun encodeHistory(
        events: List<GitEditEvent>,
        files: MutableMap<String, ByteArray>,
    ): Pair<String, ByteArray>? {
        if (events.isEmpty()) return null
        val first = events.minOf { it.occurredAt }
        val instant = java.time.Instant.ofEpochMilli(first).atZone(java.time.ZoneOffset.UTC)
        val digest = sha256(events.joinToString("|") { it.id }.toByteArray(Charsets.UTF_8)).take(16)
        val path = "history/%04d/%02d/%02d/%d-%s.jsonl".format(
            instant.year,
            instant.monthValue,
            instant.dayOfMonth,
            first,
            digest,
        )
        val output = StringBuilder()
        events.forEach { event ->
            val columns = event.columns.split(',').filter { it.isNotBlank() }
            val before = historyPayload(event.beforePayload, columns, files)
            val after = historyPayload(event.afterPayload, columns, files)
            output.append(
                JSONObject()
                    .put("format_version", 1)
                    .put("event_id", event.id)
                    .put("timestamp_ms", event.occurredAt)
                    .put("author", event.author)
                    .put("group_id", event.groupId ?: JSONObject.NULL)
                    .put("table", event.table)
                    .put("operation", event.operation.lowercase())
                    .put("row_key", event.rowKey)
                    .put("changed_columns", JSONArray(changedColumns(event).split(',').filter { it.isNotBlank() }))
                    .put("before", before ?: JSONObject.NULL)
                    .put("after", after ?: JSONObject.NULL)
                    .toString(),
            ).append('\n')
        }
        return path to output.toString().toByteArray(Charsets.UTF_8)
    }

    private fun changedColumns(event: GitEditEvent): String {
        val columns = event.columns.split(',').filter { it.isNotBlank() }
        val before = decodedPayload(event.beforePayload, columns)
        val after = decodedPayload(event.afterPayload, columns)
        if (before == null || after == null) return columns.joinToString(",")
        return columns.filter { column -> !rawEquals(before[column], after[column]) }.joinToString(",")
    }

    private fun decodedPayload(payload: String?, columns: List<String>): Map<String, Any?>? {
        if (payload == null) return null
        val values = SyncJournal.keyValues(payload)
        require(values.size == columns.size) { "Git history payload shape mismatch" }
        return columns.indices.associate { columns[it] to values[it] }
    }

    private fun historyPayload(
        payload: String?,
        columns: List<String>,
        files: MutableMap<String, ByteArray>,
    ): JSONObject? {
        val decoded = decodedPayload(payload, columns) ?: return null
        return JSONObject().also { target ->
            decoded.forEach { (column, value) ->
                target.put(column, historyValue(value, files))
            }
        }
    }

    private fun historyValue(value: Any?, files: MutableMap<String, ByteArray>): Any =
        when (value) {
            null -> JSONObject.NULL
            is ByteArray -> {
                val hash = sha256(value)
                val path = "objects/sha256/" + hash.take(2) + "/" + hash + ".bin"
                files.putIfAbsent(path, value)
                JSONObject()
                    .put("\$object", hash)
                    .put("path", path)
                    .put("size", value.size)
            }
            is Number, is String, is Boolean -> value
            else -> value.toString()
        }

    private fun rawEquals(left: Any?, right: Any?): Boolean {
        if (left is ByteArray && right is ByteArray) return left.contentEquals(right)
        if (left is Number && right is Number) {
            return runCatching {
                BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
            }.getOrDefault(left.toDouble() == right.toDouble())
        }
        return left == right
    }

    private fun cachedEntries(manifest: JSONObject?): Map<String, JSONObject> {
        val array = manifest?.optJSONArray("tables") ?: return emptyMap()
        return buildMap {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                put(item.getString("name"), item)
            }
        }
    }

    private fun tableNames(db: SQLiteDatabase): List<String> =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val table = cursor.getString(0)
                    if (table !in excludedTables) add(table)
                }
            }
        }

    private fun generation(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT generation FROM hub_generation WHERE id=1", null).use {
            require(it.moveToFirst()) { "Missing database generation" }
            it.getLong(0)
        }

    private data class TableSnapshot(
        val entry: JSONObject,
        val files: Map<String, ByteArray>,
    )

    private fun exportTableShards(
        db: SQLiteDatabase,
        table: String,
        objectFiles: MutableMap<String, ByteArray>,
    ): TableSnapshot {
        requireSafeIdentifier(table)
        val columns = db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    Triple(cursor.getString(1), cursor.getInt(5), cursor.getInt(0)),
                )
            }
        }
        require(columns.isNotEmpty()) { "Missing table: $table" }
        val primary = columns.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
        val order = if (primary.isNotEmpty()) primary.joinToString(",") { "`$it`" } else "rowid"
        val totalRows = db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use {
            require(it.moveToFirst())
            it.getInt(0)
        }
        val shardCount = when {
            totalRows <= 500 -> 1
            totalRows <= 5_000 -> 8
            totalRows <= 50_000 -> 32
            else -> 128
        }
        val builders = Array(shardCount) { StringBuilder() }
        val counts = IntArray(shardCount)
        db.rawQuery("SELECT * FROM `$table` ORDER BY $order", null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (index in 0 until cursor.columnCount) {
                    row.put(
                        cursor.getColumnName(index),
                        encodeCursorValue(cursor, index, objectFiles),
                    )
                }
                val key = if (primary.isEmpty()) row.toString()
                    else primary.joinToString("|") { row.opt(it)?.toString().orEmpty() }
                val hash = sha256(key.toByteArray(Charsets.UTF_8))
                val shard = if (shardCount == 1) 0
                    else java.lang.Long.parseUnsignedLong(hash.take(8), 16).rem(shardCount).toInt()
                builders[shard].append(row.toString()).append('\n')
                counts[shard]++
            }
        }
        val shardFiles = linkedMapOf<String, ByteArray>()
        val shardManifest = JSONArray()
        builders.indices.forEach { index ->
            val path = "state/tables/" + table + "/" + "%03d".format(index) + ".jsonl"
            val bytes = builders[index].toString().toByteArray(Charsets.UTF_8)
            shardFiles[path] = bytes
            shardManifest.put(
                JSONObject()
                    .put("path", path)
                    .put("sha256", sha256(bytes))
                    .put("rows", counts[index]),
            )
        }
        return TableSnapshot(
            entry = JSONObject()
                .put("name", table)
                .put("rows", totalRows)
                .put("shard_count", shardCount)
                .put("shards", shardManifest),
            files = shardFiles,
        )
    }

    private fun rowCount(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var count = 0
        bytes.forEach { if (it == '\n'.code.toByte()) count++ }
        return count
    }

    private fun encodeCursorValue(
        cursor: Cursor,
        index: Int,
        files: MutableMap<String, ByteArray>,
    ): Any =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> {
                val value = cursor.getDouble(index)
                if (value.isFinite()) value
                else JSONObject().put("\$real", value.toString())
            }
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
            Cursor.FIELD_TYPE_BLOB -> {
                val bytes = cursor.getBlob(index)
                val hash = sha256(bytes)
                val path = "objects/sha256/" + hash.take(2) + "/" + hash + ".bin"
                files.putIfAbsent(path, bytes)
                JSONObject()
                    .put("\$object", hash)
                    .put("path", path)
                    .put("size", bytes.size)
            }
            else -> error("Unsupported SQLite value")
        }

    fun decodeJsonValue(value: Any?): Any? =
        when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> when {
                value.has("\$base64") ->
                    Base64.decode(value.getString("\$base64"), Base64.DEFAULT)
                value.has("\$real") -> when (value.getString("\$real")) {
                    "NaN" -> Double.NaN
                    "Infinity" -> Double.POSITIVE_INFINITY
                    "-Infinity" -> Double.NEGATIVE_INFINITY
                    else -> error("Invalid encoded real")
                }
                else -> error("Unsupported encoded SQLite value")
            }
            is Boolean -> if (value) 1L else 0L
            is Number, is String -> value
            else -> error("Unsupported JSON value")
        }

    fun putValue(values: ContentValues, column: String, value: Any?) {
        when (val decoded = decodeJsonValue(value)) {
            null -> values.putNull(column)
            is ByteArray -> values.put(column, decoded)
            is String -> values.put(column, decoded)
            is Int -> values.put(column, decoded)
            is Long -> values.put(column, decoded)
            is Float -> values.put(column, decoded)
            is Double -> values.put(column, decoded)
            is Number -> values.put(column, decoded.toString())
            else -> error("Unsupported SQLite value for $column")
        }
    }

    fun jsonValueEquals(expected: Any?, actual: Any?): Boolean {
        val left = decodeJsonValue(expected)
        val right = decodeJsonValue(actual)
        if (left is ByteArray && right is ByteArray) return left.contentEquals(right)
        if (left is Number && right is Number) {
            return runCatching {
                BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
            }.getOrDefault(left.toDouble() == right.toDouble())
        }
        return left == right
    }

    private fun tablePath(table: String): String {
        requireSafeIdentifier(table)
        return "state/tables/$table.jsonl"
    }

    fun requireSafeIdentifier(value: String) {
        require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Unsafe database identifier"
        }
    }

    private fun appVersion(context: Context): Long =
        runCatching {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrDefault(0L)
}

internal object GitPatchEngine {
    fun apply(context: Context, patchBytes: ByteArray) {
        val patch = JSONObject(String(patchBytes, Charsets.UTF_8))
        require(patch.getInt("format_version") == 1) { "Unsupported patch format" }
        require(patch.getInt("schema_version") == PersonalHubDatabase.SCHEMA_VERSION) {
            "Patch targets a different database schema"
        }
        val operations = patch.getJSONArray("operations")
        val patchId = patch.optString("patch_id").ifBlank { "remote-patch" }
        val author = patch.optString("author").ifBlank { "chatgpt" }
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.beginTransaction()
        try {
            GitDataTracking.setEditContext(db, author, patchId)
            for (i in 0 until operations.length()) applyOperation(db, operations.getJSONObject(i))
            db.query("PRAGMA foreign_key_check").use {
                require(!it.moveToFirst()) { "Patch would break database relationships" }
            }
            db.setTransactionSuccessful()
        } finally {
            GitDataTracking.clearEditContext(db)
            db.endTransaction()
        }
    }

    private fun applyOperation(db: SupportSQLiteDatabase, operation: JSONObject) {
        val table = operation.getString("table")
        GitDataFormat.requireSafeIdentifier(table)
        require(table !in SyncJournal.excluded && table != GitDataTracking.TABLE) {
            "Patch cannot modify operational table"
        }
        val columns = db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    Triple(cursor.getString(1), cursor.getInt(5), cursor.getInt(0)),
                )
            }
        }
        require(columns.isNotEmpty()) { "Unknown patch table: $table" }
        val allowed = columns.map { it.first }.toSet()
        val primary = columns.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
        require(primary.isNotEmpty()) { "Patch table has no primary key" }

        val key = operation.getJSONObject("key")
        require(key.keys().asSequence().toSet() == primary.toSet()) {
            "Patch key does not match primary key"
        }
        val whereArgs = primary.map { GitDataFormat.decodeJsonValue(key.get(it)) }.toTypedArray()
        val where = primary.joinToString(" AND ") { "`$it`=?" }
        val current = readRow(db, table, allowed, where, whereArgs)
        val op = operation.getString("op")

        val expected = operation.optJSONObject("expect")
        if (expected != null) {
            require(current != null) { "Patch precondition row is missing" }
            expected.keys().forEach { column ->
                require(column in allowed) { "Unknown patch column: $column" }
                require(GitDataFormat.jsonValueEquals(expected.get(column), current.get(column))) {
                    "Patch precondition failed for $table.$column"
                }
            }
        }

        when (op) {
            "insert" -> {
                require(current == null) { "Patch insert row already exists" }
                val values = values(operation.getJSONObject("values"), allowed)
                primary.forEach { column ->
                    if (!values.containsKey(column)) {
                        GitDataFormat.putValue(values, column, key.get(column))
                    }
                }
                require(db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values) != -1L) {
                    "Patch insert failed"
                }
            }
            "update" -> {
                require(current != null) { "Patch update row is missing" }
                val payload = operation.getJSONObject("values")
                primary.forEach { require(!payload.has(it)) { "Patch cannot change a primary key" } }
                val values = values(payload, allowed)
                require(values.size() > 0) { "Patch update has no values" }
                require(db.update(table, SQLiteDatabase.CONFLICT_ABORT, values, where, whereArgs) == 1) {
                    "Patch update matched an unexpected number of rows"
                }
            }
            "delete" -> {
                require(current != null) { "Patch delete row is missing" }
                require(db.delete(table, where, whereArgs) == 1) {
                    "Patch delete matched an unexpected number of rows"
                }
            }
            else -> error("Unsupported patch operation: $op")
        }
    }

    private fun values(source: JSONObject, allowed: Set<String>): ContentValues =
        ContentValues().also { values ->
            source.keys().forEach { column ->
                require(column in allowed) { "Unknown patch column: $column" }
                GitDataFormat.putValue(values, column, source.get(column))
            }
        }

    private fun readRow(
        db: SupportSQLiteDatabase,
        table: String,
        columns: Set<String>,
        where: String,
        args: Array<Any?>,
    ): JSONObject? {
        val ordered = columns.sorted()
        return db.query(
            "SELECT ${ordered.joinToString(",") { "`$it`" }} FROM `$table` WHERE $where LIMIT 2",
            args,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val row = JSONObject()
            for (i in ordered.indices) row.put(ordered[i], encodeSupportValue(cursor, i))
            require(!cursor.moveToNext()) { "Patch key is not unique" }
            row
        }
    }

    private fun encodeSupportValue(cursor: Cursor, index: Int): Any =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
            Cursor.FIELD_TYPE_BLOB -> JSONObject().put(
                "\$base64",
                Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP),
            )
            else -> error("Unsupported SQLite value")
        }
}

internal object GitStateRestorer {
    fun restore(
        context: Context,
        transport: GitHubDataTransport,
        revision: String,
        control: JSONObject?,
        controlRef: String,
    ) {
        require(revision.matches(Regex("[A-Fa-f0-9]{7,40}|[A-Za-z0-9._/-]+"))) {
            "Invalid Git revision"
        }
        val manifestBytes = transport.readFile(GIT_STATE_MANIFEST, revision)
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.getInt("format_version") in setOf(1, 2)) { "Unsupported state format" }
        val schemaVersion = manifest.getInt("schema_version")
        require(schemaVersion in 1..PersonalHubDatabase.SCHEMA_VERSION) {
            "The selected revision uses a newer unsupported schema"
        }
        val stage = File(context.cacheDir, "personalhub-git-restore-${UUID.randomUUID()}.db")
        try {
            createDatabaseForSchema(context, stage, schemaVersion)
            populate(context, transport, revision, manifest, stage)
            if (schemaVersion < PersonalHubDatabase.SCHEMA_VERSION) {
                if (GitRemoteMigrationEngine.canMigrate(
                        control,
                        schemaVersion,
                        PersonalHubDatabase.SCHEMA_VERSION,
                    )
                ) {
                    GitRemoteMigrationEngine.migrate(
                        context = context,
                        transport = transport,
                        ref = controlRef,
                        control = requireNotNull(control),
                        file = stage,
                        from = schemaVersion,
                        to = PersonalHubDatabase.SCHEMA_VERSION,
                    )
                } else {
                    PersonalHubDatabase.openTemporary(context, stage.absolutePath).let { temporary ->
                        try {
                            temporary.openHelper.writableDatabase
                        } finally {
                            temporary.close()
                        }
                    }
                }
            }
            DatabaseVault.validate(context, stage)
            DatabaseVault.importDatabaseFile(context, stage)
        } finally {
            stage.delete()
            listOf("-wal", "-shm", "-journal").forEach { File(stage.path + it).delete() }
        }
    }

    private fun createDatabaseForSchema(context: Context, target: File, version: Int) {
        if (target.exists()) target.delete()
        val schema = JSONObject(
            context.assets.open(
                "com.gernalix.personalhub.core.database.PersonalHubDatabase/$version.json",
            ).bufferedReader().use { it.readText() },
        ).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(target, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", table),
                    )
                }
            }
            val views = schema.optJSONArray("views") ?: JSONArray()
            for (i in 0 until views.length()) {
                db.execSQL(views.getJSONObject(i).getString("createSql"))
            }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,?)",
                arrayOf(schema.getString("identityHash")),
            )
            db.version = version
            runCatching {
                db.execSQL("INSERT OR IGNORE INTO hub_generation(id,generation) VALUES(1,0)")
            }
        }
    }

    private fun populate(
        context: Context,
        transport: GitHubDataTransport,
        revision: String,
        manifest: JSONObject,
        target: File,
    ) {
        val generation = manifest.getLong("generation")
        val tables = manifest.getJSONArray("tables")
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.beginTransaction()
            try {
                for (i in 0 until tables.length()) {
                    val entry = tables.getJSONObject(i)
                    val table = entry.getString("name")
                    GitDataFormat.requireSafeIdentifier(table)
                    val shards = entry.optJSONArray("shards")
                    if (shards == null) {
                        val bytes = transport.readFile(entry.getString("path"), revision)
                        require(GitDataFormat.sha256(bytes) == entry.getString("sha256")) {
                            "State file hash mismatch: $table"
                        }
                        importTable(db, table, bytes, transport, revision)
                    } else {
                        for (j in 0 until shards.length()) {
                            val shard = shards.getJSONObject(j)
                            val bytes = transport.readFile(shard.getString("path"), revision)
                            require(GitDataFormat.sha256(bytes) == shard.getString("sha256")) {
                                "State shard hash mismatch: $table"
                            }
                            importTable(db, table, bytes, transport, revision)
                        }
                    }
                }
                runCatching {
                    db.execSQL(
                        "INSERT OR REPLACE INTO hub_generation(id,generation) VALUES(1,?)",
                        arrayOf(generation),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("PRAGMA foreign_keys=ON")
            db.rawQuery("PRAGMA foreign_key_check", null).use {
                require(!it.moveToFirst()) { "Restored state has invalid relationships" }
            }
            db.rawQuery("PRAGMA quick_check", null).use {
                require(it.moveToFirst() && it.getString(0) == "ok") {
                    "Restored database failed integrity check"
                }
            }
        }
    }

    private fun importTable(
        db: SQLiteDatabase,
        table: String,
        bytes: ByteArray,
        transport: GitHubDataTransport,
        revision: String,
    ) {
        val allowed = db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        require(allowed.isNotEmpty()) { "State references a table absent from its schema: $table" }
        String(bytes, Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val row = JSONObject(line)
            val values = ContentValues()
            row.keys().forEach { column ->
                require(column in allowed) { "State contains unknown column: $table.$column" }
                val value = row.get(column)
                if (value is JSONObject && value.has("\$object")) {
                    val path = value.getString("path")
                    val blob = transport.readFile(path, revision)
                    require(GitDataFormat.sha256(blob) == value.getString("\$object")) {
                        "State object hash mismatch: $path"
                    }
                    values.put(column, blob)
                } else {
                    GitDataFormat.putValue(values, column, value)
                }
            }
            db.insertOrThrow(table, null, values)
        }
    }
}
