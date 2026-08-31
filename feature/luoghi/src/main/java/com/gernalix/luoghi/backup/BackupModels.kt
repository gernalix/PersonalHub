package com.gernalix.luoghi.backup

import android.net.Uri
import com.gernalix.luoghi.data.LuoghiSnapshot

object LuoghiBackupFormat {
    const val ID = "com.gernalix.luoghi.sqlite-backup"
    const val CURRENT_VERSION = 2
    const val CURRENT_SCHEMA = 5
    const val MAX_BACKUP_BYTES = 32L * 1024L * 1024L
}

data class BackupSource(
    val uri: Uri,
    val displayName: String,
    val lastModifiedMs: Long? = null,
    val manifestJson: String? = null,
)

data class BackupPreview(
    val source: BackupSource,
    val exportedAtMs: Long,
    val appVersion: String,
    val formatVersion: Int,
    val sourceSchemaVersion: Int,
    val sizeBytes: Long,
    val sha256: String,
    val backupUuid: String?,
    val placeCount: Int,
    val eventCount: Int,
    val tableCounts: Map<String, Int>,
    val warnings: List<BackupWarning>,
)

enum class BackupWarning {
    LEGACY_MANIFEST,
    CHECKSUM_NOT_DECLARED,
    PREVIOUS_SCHEMA_MIGRATED,
}

enum class BackupValidationCode {
    FILE_EMPTY,
    FILE_TOO_LARGE,
    NOT_SQLITE,
    CORRUPT_DATABASE,
    UNSUPPORTED_FUTURE_FORMAT,
    UNSUPPORTED_FUTURE_SCHEMA,
    UNSUPPORTED_OLD_SCHEMA,
    WRONG_APPLICATION,
    CHECKSUM_MISMATCH,
    MANIFEST_MISMATCH,
    REQUIRED_DATA_MISSING,
    INVALID_UUID,
    FOREIGN_KEY_VIOLATION,
    EMPTY_BACKUP,
    CANNOT_READ,
}

class BackupValidationException(
    val code: BackupValidationCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

data class ValidatedBackup(
    val preview: BackupPreview,
    val snapshot: LuoghiSnapshot,
)

data class BackupDiscovery(
    val compatible: List<ValidatedBackup>,
    val rejected: List<RejectedBackup>,
) {
    val preferred: ValidatedBackup?
        get() = compatible.maxByOrNull { it.preview.exportedAtMs }
}

data class RejectedBackup(
    val source: BackupSource,
    val code: BackupValidationCode,
)

data class RestoreSummary(
    val places: Int,
    val events: Int,
    val aliases: Int,
    val links: Int,
    val auditRows: Int,
    val historyActions: Int,
    val activeCheckIns: Int,
    val preventiveBackupFile: String?,
    val finalExportSucceeded: Boolean,
)

sealed interface RestoreResult {
    data class Success(val summary: RestoreSummary) : RestoreResult
    data class InvalidBackup(val code: BackupValidationCode) : RestoreResult
    data class Failure(val message: String) : RestoreResult
}
