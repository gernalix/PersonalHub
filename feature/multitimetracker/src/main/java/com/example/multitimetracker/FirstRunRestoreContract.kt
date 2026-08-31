package com.example.multitimetracker

import com.example.multitimetracker.persistence.BackupFolderPolicy

enum class FirstRunContinuationMode {
    CURRENT_DATA,
    EMPTY_SETUP,
}

enum class BackupFolderInspectionKind {
    EMPTY,
    RESTORABLE_DB_FOUND,
    LEGACY_BACKUP_ONLY,
    NO_RESTORABLE_DATA,
}

data class BackupFolderInspection(
    val folderLabel: String,
    val kind: BackupFolderInspectionKind,
    val evidenceFileNames: List<String> = emptyList(),
)

enum class FirstRunFallbackReason {
    NO_RESTORABLE_DATA_FOUND,
    LEGACY_BACKUP_ONLY,
    USER_SKIPPED_RESTORE,
    RESTORE_FAILED,
}

sealed interface FirstRunSetupState {
    data object NoFolderChosen : FirstRunSetupState

    data class FolderChosenEmpty(
        val folderLabel: String,
        val continuationMode: FirstRunContinuationMode,
    ) : FirstRunSetupState

    data class ExistingDataFound(
        val folderLabel: String,
        val evidenceFileNames: List<String>,
        val continuationMode: FirstRunContinuationMode,
    ) : FirstRunSetupState

    data class RestoreSucceeded(
        val folderLabel: String,
        val evidenceFileNames: List<String>,
    ) : FirstRunSetupState

    data class RestoreFailed(
        val folderLabel: String,
        val evidenceFileNames: List<String>,
        val continuationMode: FirstRunContinuationMode,
    ) : FirstRunSetupState

    data class FallbackToContinuation(
        val folderLabel: String,
        val reason: FirstRunFallbackReason,
        val continuationMode: FirstRunContinuationMode,
        val evidenceFileNames: List<String> = emptyList(),
    ) : FirstRunSetupState
}

private val canonicalSupportEntries = setOf("vaults", "exports", "logs", "tmp")
private val legacyCsvEntries = setOf(
    "sessions.csv",
    "totals.csv",
    "tag_sessions.csv",
    "tag_totals.csv",
    "app_usage.csv",
    "dict.json",
)

fun inspectBackupFolderEntries(
    folderLabel: String,
    entryNames: List<String>,
): BackupFolderInspection {
    val normalizedEntries = entryNames
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val lowerEntries = normalizedEntries.map { it.lowercase() }

    val restorableFiles = BackupFolderPolicy.restorableDbNames(normalizedEntries)
    if (restorableFiles.isNotEmpty()) {
        return BackupFolderInspection(
            folderLabel = folderLabel,
            kind = BackupFolderInspectionKind.RESTORABLE_DB_FOUND,
            evidenceFileNames = restorableFiles,
        )
    }

    if (normalizedEntries.isEmpty()) {
        return BackupFolderInspection(
            folderLabel = folderLabel,
            kind = BackupFolderInspectionKind.EMPTY,
        )
    }

    val legacyFiles = normalizedEntries.filter { it.lowercase() in legacyCsvEntries }
    val nonSupportEntries = normalizedEntries.filter { it.lowercase() !in canonicalSupportEntries }
    if (legacyFiles.isNotEmpty() && nonSupportEntries.all { it.lowercase() in legacyCsvEntries }) {
        return BackupFolderInspection(
            folderLabel = folderLabel,
            kind = BackupFolderInspectionKind.LEGACY_BACKUP_ONLY,
            evidenceFileNames = legacyFiles.sorted(),
        )
    }

    return BackupFolderInspection(
        folderLabel = folderLabel,
        kind = BackupFolderInspectionKind.NO_RESTORABLE_DATA,
        evidenceFileNames = normalizedEntries.sorted(),
    )
}

fun decideFirstRunSetupState(
    hasChosenFolder: Boolean,
    inspection: BackupFolderInspection?,
    continuationMode: FirstRunContinuationMode,
): FirstRunSetupState {
    if (!hasChosenFolder || inspection == null) {
        return FirstRunSetupState.NoFolderChosen
    }
    return when (inspection.kind) {
        BackupFolderInspectionKind.EMPTY -> FirstRunSetupState.FolderChosenEmpty(
            folderLabel = inspection.folderLabel,
            continuationMode = continuationMode,
        )

        BackupFolderInspectionKind.RESTORABLE_DB_FOUND -> FirstRunSetupState.ExistingDataFound(
            folderLabel = inspection.folderLabel,
            evidenceFileNames = inspection.evidenceFileNames,
            continuationMode = continuationMode,
        )

        BackupFolderInspectionKind.LEGACY_BACKUP_ONLY -> FirstRunSetupState.FallbackToContinuation(
            folderLabel = inspection.folderLabel,
            reason = FirstRunFallbackReason.LEGACY_BACKUP_ONLY,
            continuationMode = continuationMode,
            evidenceFileNames = inspection.evidenceFileNames,
        )

        BackupFolderInspectionKind.NO_RESTORABLE_DATA -> FirstRunSetupState.FallbackToContinuation(
            folderLabel = inspection.folderLabel,
            reason = FirstRunFallbackReason.NO_RESTORABLE_DATA_FOUND,
            continuationMode = continuationMode,
            evidenceFileNames = inspection.evidenceFileNames,
        )
    }
}
