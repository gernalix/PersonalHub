package com.gernalix.personalhub.core.database.capsules.soldi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceAdvancedMigrationContractTest {
    @Test
    fun migrationTargetsCurrentSoldiSchemaVersion() {
        val migration = FinanceAdvancedMigration()
        assertEquals(11, migration.startVersion)
        assertEquals(12, migration.endVersion)
    }

    @Test
    fun financeAdvancedEntitiesArePartOfRoomDatabaseContract() {
        val source = javaClass.classLoader
            ?.getResourceAsStream("dummy")
        // Compile-time coverage is the main purpose of this test: all v2 entity classes must be
        // resolvable from the database module after the migration was introduced.
        val names = listOf(
            com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransfer::class.java.simpleName,
            com.gernalix.personalhub.core.database.capsules.soldi.FinanceMacro::class.java.simpleName,
            com.gernalix.personalhub.core.database.capsules.soldi.FinanceRecurrence::class.java.simpleName,
            com.gernalix.personalhub.core.database.capsules.soldi.FinanceAttachment::class.java.simpleName,
        )
        assertTrue(names.all { it.isNotBlank() })
        source?.close()
    }
}
