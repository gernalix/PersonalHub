package com.gernalix.personalhub

import com.gernalix.personalhub.core.database.AutoExportStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAutoExportStatusTest {
    @Test
    fun currentSuccessfulExportIsHealthy() {
        val ui = homeAutoExportUiStatus(
            AutoExportStatus(true, 7, 7, 1234L, null, false),
        )
        assertTrue(ui.healthy)
    }

    @Test
    fun missingFolderStaleOrErrorIsNotHealthy() {
        assertFalse(homeAutoExportUiStatus(AutoExportStatus(false, 7, 7, 0L, null, false)).healthy)
        assertFalse(homeAutoExportUiStatus(AutoExportStatus(true, 8, 7, 1234L, null, true)).healthy)
        assertFalse(homeAutoExportUiStatus(AutoExportStatus(true, 7, 7, 1234L, "failed", false)).healthy)
        assertFalse(homeAutoExportUiStatus(null).healthy)
    }
}
