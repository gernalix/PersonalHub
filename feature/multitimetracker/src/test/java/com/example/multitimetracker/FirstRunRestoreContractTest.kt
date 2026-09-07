package com.example.multitimetracker

import com.example.multitimetracker.persistence.BackupFolderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunRestoreContractTest {
    @Test fun personalHubDatabaseFilesAreRestorable() {
        assertEquals(
            listOf("personalhub.db", "personalhub.db.bak"),
            BackupFolderPolicy.restorableDbNames(listOf("personalhub.db.bak", "notes.txt", "personalhub.db"))
        )
    }

    @Test fun legacyTimerDatabaseFileDoesNotTriggerRestore() {
        val inspection = inspectBackupFolderEntries(
            folderLabel = "Backup",
            entryNames = listOf("multitimer.db")
        )

        assertEquals(BackupFolderInspectionKind.NO_RESTORABLE_DATA, inspection.kind)
    }

    @Test fun legacyCsvOnlyFallsBackWithoutBlockingStartup() {
        val inspection = inspectBackupFolderEntries(
            folderLabel = "Backup",
            entryNames = listOf("sessions.csv", "tag_totals.csv", "dict.json")
        )
        val state = decideFirstRunSetupState(
            hasChosenFolder = true,
            inspection = inspection,
            continuationMode = FirstRunContinuationMode.EMPTY_SETUP
        )

        assertEquals(BackupFolderInspectionKind.LEGACY_BACKUP_ONLY, inspection.kind)
        assertTrue(state is FirstRunSetupState.FallbackToContinuation)
    }
}
