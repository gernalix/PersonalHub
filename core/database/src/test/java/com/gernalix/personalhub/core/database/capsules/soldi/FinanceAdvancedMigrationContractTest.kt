package com.gernalix.personalhub.core.database.capsules.soldi

import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceAdvancedMigrationContractTest {
    @Test
    fun migrationTargetsCurrentDatabaseSchemaVersion() {
        val migration = FinanceAdvancedMigration()
        assertEquals(11, migration.startVersion)
        assertEquals(12, migration.endVersion)
        assertEquals(12, PersonalHubDatabase.SCHEMA_VERSION)
    }
}
