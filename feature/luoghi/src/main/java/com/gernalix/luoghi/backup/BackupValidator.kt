package com.gernalix.luoghi.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.gernalix.luoghi.BuildConfig
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.LuoghiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class BackupValidator(private val context: Context) {
    suspend fun inspect(source: BackupSource): ValidatedBackup = withContext(Dispatchers.IO) {
        val rawFile = File.createTempFile("luoghi-backup-", ".db", context.cacheDir)
        var staging: LuoghiDatabase? = null
        val stagingName = "restore-stage-${UUID.randomUUID()}.db"
        try {
            val size = copyWithLimit(source, rawFile)
            val sha256 = sha256(rawFile)
            val manifest = parseManifest(source.manifestJson)
            validateManifestHeader(manifest, sha256, size)
            val sourceSchema = inspectRawDatabase(rawFile)
            validateDeclaredSchema(manifest, sourceSchema)

            val stagingPath = context.getDatabasePath(stagingName)
            stagingPath.parentFile?.mkdirs()
            rawFile.copyTo(stagingPath, overwrite = true)
            staging = LuoghiDatabase.openStaging(context, stagingName)
            staging.openHelper.writableDatabase
            requireDatabaseIntegrity(staging)
            val snapshot = staging.placeDao().readSnapshot()
            validateSnapshot(snapshot)
            validateManifestCounts(manifest, snapshot)

            val warnings = buildList {
                if (manifest == null || !manifest.has("format_version")) add(BackupWarning.LEGACY_MANIFEST)
                if (manifest?.optString("database_sha256").isNullOrBlank()) add(BackupWarning.CHECKSUM_NOT_DECLARED)
                if (sourceSchema < LuoghiBackupFormat.CURRENT_SCHEMA) add(BackupWarning.PREVIOUS_SCHEMA_MIGRATED)
            }
            ValidatedBackup(
                preview = BackupPreview(
                    source = source,
                    exportedAtMs = manifest?.optLong("exported_at", 0L)
                        ?.takeIf { it > 0L }
                        ?: source.lastModifiedMs?.takeIf { it > 0L }
                        ?: 0L,
                    appVersion = manifest?.optString("version_name")?.takeIf { it.isNotBlank() }
                        ?: manifest?.optInt("version_code", 0)?.takeIf { it > 0 }?.toString()
                        ?: "schema $sourceSchema",
                    formatVersion = manifest?.optInt("format_version", 1) ?: 1,
                    sourceSchemaVersion = sourceSchema,
                    sizeBytes = size,
                    sha256 = sha256,
                    backupUuid = manifest?.optString("backup_uuid")?.takeIf { it.isNotBlank() },
                    placeCount = snapshot.places.size,
                    eventCount = snapshot.events.size,
                    tableCounts = snapshot.tableCounts,
                    warnings = warnings,
                ),
                snapshot = snapshot,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: BackupValidationException) {
            throw known
        } catch (error: Throwable) {
            throw BackupValidationException(
                BackupValidationCode.CORRUPT_DATABASE,
                error.message ?: "Backup validation failed",
                error,
            )
        } finally {
            staging?.close()
            context.deleteDatabase(stagingName)
            rawFile.delete()
        }
    }

    private fun copyWithLimit(source: BackupSource, target: File): Long {
        var total = 0L
        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw BackupValidationException(BackupValidationCode.CANNOT_READ, "Cannot read backup")
        input.use { sourceStream ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = sourceStream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > LuoghiBackupFormat.MAX_BACKUP_BYTES) {
                        throw BackupValidationException(BackupValidationCode.FILE_TOO_LARGE, "Backup is too large")
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (total == 0L) {
            throw BackupValidationException(BackupValidationCode.FILE_EMPTY, "Backup is empty")
        }
        if (!hasSqliteHeader(target)) {
            throw BackupValidationException(BackupValidationCode.NOT_SQLITE, "File is not SQLite")
        }
        return total
    }

    private fun hasSqliteHeader(file: File): Boolean {
        val expected = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val actual = ByteArray(expected.size)
        return file.inputStream().use { it.read(actual) == expected.size } && actual.contentEquals(expected)
    }

    private fun parseManifest(json: String?): JSONObject? {
        if (json.isNullOrBlank()) return null
        return runCatching { JSONObject(json) }.getOrElse {
            throw BackupValidationException(BackupValidationCode.MANIFEST_MISMATCH, "Manifest is invalid", it)
        }
    }

    private fun validateManifestHeader(manifest: JSONObject?, actualSha256: String, actualSize: Long) {
        if (manifest == null) return
        val format = manifest.optInt("format_version", 1)
        if (format < 1) {
            throw BackupValidationException(BackupValidationCode.MANIFEST_MISMATCH, "Backup format version is invalid")
        }
        if (format > LuoghiBackupFormat.CURRENT_VERSION) {
            throw BackupValidationException(
                BackupValidationCode.UNSUPPORTED_FUTURE_FORMAT,
                "Backup format $format is newer than supported",
            )
        }
        val formatId = manifest.optString("format")
        if (formatId.isNotBlank() && formatId != LuoghiBackupFormat.ID) {
            throw BackupValidationException(BackupValidationCode.WRONG_APPLICATION, "Backup format belongs to another app")
        }
        val applicationId = manifest.optString("application_id")
        if (applicationId.isNotBlank() && applicationId != BuildConfig.APPLICATION_ID) {
            throw BackupValidationException(BackupValidationCode.WRONG_APPLICATION, "Backup belongs to $applicationId")
        }
        val expectedSha256 = manifest.optString("database_sha256")
        if (expectedSha256.isNotBlank() && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
            throw BackupValidationException(BackupValidationCode.CHECKSUM_MISMATCH, "Backup checksum does not match")
        }
        val expectedSize = manifest.optLong("database_size_bytes", 0L)
        if (expectedSize < 0L || (expectedSize > 0L && expectedSize != actualSize)) {
            throw BackupValidationException(BackupValidationCode.MANIFEST_MISMATCH, "Backup size does not match manifest")
        }
        manifest.optString("backup_uuid").takeIf { it.isNotBlank() }?.let { requireUuid(it) }
        if (format >= LuoghiBackupFormat.CURRENT_VERSION) {
            val requiredKeys = listOf(
                "format",
                "format_version",
                "schema_version",
                "backup_uuid",
                "application_id",
                "version_code",
                "version_name",
                "exported_at",
                "database",
                "database_size_bytes",
                "database_sha256",
            ) + EXPECTED_TABLES
            if (requiredKeys.any { key -> !manifest.has(key) || manifest.isNull(key) }) {
                throw BackupValidationException(
                    BackupValidationCode.MANIFEST_MISMATCH,
                    "Current backup manifest is incomplete",
                )
            }
            if (
                manifest.optString("format").isBlank() ||
                manifest.optString("application_id").isBlank() ||
                manifest.optString("backup_uuid").isBlank() ||
                manifest.optString("database_sha256").isBlank() ||
                manifest.optString("version_name").isBlank()
            ) {
                throw BackupValidationException(
                    BackupValidationCode.MANIFEST_MISMATCH,
                    "Current backup manifest fields are empty",
                )
            }
            if (manifest.optString("database") != com.gernalix.luoghi.export.LuoghiExporter.DB_FILE) {
                throw BackupValidationException(BackupValidationCode.MANIFEST_MISMATCH, "Manifest database name is invalid")
            }
            if (manifest.optLong("exported_at", 0L) <= 0L || manifest.optInt("version_code", 0) <= 0) {
                throw BackupValidationException(BackupValidationCode.MANIFEST_MISMATCH, "Manifest metadata is invalid")
            }
        }
    }

    private fun inspectRawDatabase(file: File): Int {
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    val ok = cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                    if (!ok) {
                        throw BackupValidationException(BackupValidationCode.CORRUPT_DATABASE, "SQLite integrity check failed")
                    }
                }
                db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        throw BackupValidationException(
                            BackupValidationCode.FOREIGN_KEY_VIOLATION,
                            "Backup contains broken references",
                        )
                    }
                }
                val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                when {
                    version > LuoghiBackupFormat.CURRENT_SCHEMA -> throw BackupValidationException(
                        BackupValidationCode.UNSUPPORTED_FUTURE_SCHEMA,
                        "Schema $version is newer than supported",
                    )
                    version < 1 -> throw BackupValidationException(
                        BackupValidationCode.UNSUPPORTED_OLD_SCHEMA,
                        "Schema $version is not supported",
                    )
                    else -> version
                }
            }
        } catch (known: BackupValidationException) {
            throw known
        } catch (error: Throwable) {
            throw BackupValidationException(BackupValidationCode.CORRUPT_DATABASE, "Cannot open SQLite backup", error)
        }
    }

    private fun validateDeclaredSchema(manifest: JSONObject?, actualSchema: Int) {
        val declared = manifest?.optInt("schema_version", 0) ?: 0
        if (declared > LuoghiBackupFormat.CURRENT_SCHEMA) {
            throw BackupValidationException(
                BackupValidationCode.UNSUPPORTED_FUTURE_SCHEMA,
                "Declared schema $declared is newer than supported",
            )
        }
        if (declared > 0 && declared != actualSchema) {
            throw BackupValidationException(BackupValidationCode.MANIFEST_MISMATCH, "Manifest schema does not match database")
        }
    }

    private fun requireDatabaseIntegrity(database: LuoghiDatabase) {
        database.query("PRAGMA integrity_check", null).use { cursor ->
            if (!cursor.moveToFirst() || !cursor.getString(0).equals("ok", ignoreCase = true)) {
                throw BackupValidationException(BackupValidationCode.CORRUPT_DATABASE, "Migrated backup is corrupt")
            }
        }
        database.query("PRAGMA foreign_key_check", null).use { cursor ->
            if (cursor.moveToFirst()) {
                throw BackupValidationException(
                    BackupValidationCode.FOREIGN_KEY_VIOLATION,
                    "Migrated backup contains broken references",
                )
            }
        }
    }

    private fun validateSnapshot(snapshot: LuoghiSnapshot) {
        if (snapshot.isEmpty) {
            throw BackupValidationException(BackupValidationCode.EMPTY_BACKUP, "Backup contains no user data")
        }
        val placeIds = snapshot.places.map { place ->
            requireUuid(place.uuid)
            validateCoordinates(place.lat, place.lon)
            if (place.radiusM != null && (place.radiusM ?: 0.0) <= 0.0) missingRequired("Place radius is invalid")
            if (place.createdAt <= 0L || place.updatedAt <= 0L) missingRequired("Place timestamp is invalid")
            place.uuid
        }.toSet()
        snapshot.events.forEach { event ->
            requireUuid(event.eventUuid)
            requireUuid(event.sessionUuid)
            requireReference(event.placeId, placeIds)
            validateCoordinates(event.lat, event.lon)
            if (event.timestamp <= 0L || event.accuracyM?.let { it < 0.0 } == true) {
                missingRequired("Event timestamp or accuracy is invalid")
            }
            if (event.eventType.isBlank()) {
                throw BackupValidationException(BackupValidationCode.REQUIRED_DATA_MISSING, "Event type is missing")
            }
        }
        snapshot.aliases.forEach { alias ->
            requireReference(alias.placeUuid, placeIds)
            if (alias.alias.isBlank()) missingRequired("Alias value is missing")
            if (alias.createdAt <= 0L || alias.updatedAt <= 0L) missingRequired("Alias timestamp is invalid")
        }
        snapshot.links.forEach { link ->
            requireReference(link.placeUuid, placeIds)
            if (link.ownerApp.isBlank() || link.ownerType.isBlank() || link.ownerId.isBlank()) {
                missingRequired("Provider link fields are missing")
            }
            if (link.createdAt <= 0L || link.updatedAt <= 0L) missingRequired("Provider link timestamp is invalid")
        }
        snapshot.routeDistanceCache.forEach { row ->
            requireReference(row.originPlaceId, placeIds)
            requireReference(row.destinationPlaceId, placeIds)
            if (row.distanceMeters < 0L || row.durationSeconds < 0L) missingRequired("Route cache is invalid")
        }
        snapshot.globalStats.forEach {
            if (it.id != 1 || it.firstCheckInAtGlobal <= 0L) missingRequired("Global stats state is invalid")
        }
        snapshot.historyAuditLog.forEach { row ->
            requireUuid(row.auditUuid)
            row.actionUuid?.takeIf { it.isNotBlank() }?.let(::requireUuid)
            if (row.action.isBlank() || row.entityType.isBlank() || row.entityId.isBlank()) {
                missingRequired("Audit fields are missing")
            }
            if (row.operatedAt <= 0L) missingRequired("Audit timestamp is invalid")
            row.beforeJson?.let(::requireJson)
            row.afterJson?.let(::requireJson)
        }
        snapshot.historyActions.forEach { row ->
            requireUuid(row.actionUuid)
            if (row.actionType.isBlank() || row.entityId.isBlank() || row.beforeJson.isBlank()) {
                missingRequired("Undo/redo fields are missing")
            }
            if (row.createdAt <= 0L || row.updatedAt <= 0L) missingRequired("Undo/redo timestamp is invalid")
            requireJson(row.beforeJson)
            row.afterJson?.let(::requireJson)
        }
        val activeSessions = snapshot.events
            .filter { it.eventType == PlaceEventTypes.CHECK_IN }
            .groupBy { it.sessionUuid }
        check(activeSessions.size <= snapshot.events.size)
    }

    private fun validateManifestCounts(manifest: JSONObject?, snapshot: LuoghiSnapshot) {
        if (manifest == null) return
        snapshot.tableCounts.forEach { (table, actual) ->
            if (manifest.has(table) && manifest.optInt(table, -1) != actual) {
                throw BackupValidationException(
                    BackupValidationCode.MANIFEST_MISMATCH,
                    "Manifest count for $table does not match database",
                )
            }
        }
    }

    private fun validateCoordinates(latitude: Double?, longitude: Double?) {
        if ((latitude == null) != (longitude == null)) missingRequired("Coordinate pair is incomplete")
        if (latitude != null && (latitude !in -90.0..90.0 || longitude!! !in -180.0..180.0)) {
            missingRequired("Coordinates are outside valid range")
        }
    }

    private fun requireJson(value: String) {
        val parsed = runCatching { JSONTokener(value).nextValue() }.getOrNull()
        if (parsed !is JSONObject && parsed !is JSONArray) {
            missingRequired("History JSON is invalid")
        }
    }

    private fun requireUuid(value: String) {
        if (runCatching { UUID.fromString(value) }.isFailure) {
            throw BackupValidationException(BackupValidationCode.INVALID_UUID, "Invalid UUID in backup")
        }
    }

    private fun requireReference(value: String, placeIds: Set<String>) {
        if (value !in placeIds) {
            throw BackupValidationException(BackupValidationCode.FOREIGN_KEY_VIOLATION, "Missing place reference")
        }
    }

    private fun missingRequired(message: String): Nothing =
        throw BackupValidationException(BackupValidationCode.REQUIRED_DATA_MISSING, message)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val EXPECTED_TABLES = listOf(
            "places",
            "place_aliases",
            "place_links",
            "place_events",
            "global_stats_state",
            "route_distance_cache",
            "history_audit_log",
            "history_actions",
            "place_geofence_configs",
            "place_geofence_transition_log",
        )
    }
}
