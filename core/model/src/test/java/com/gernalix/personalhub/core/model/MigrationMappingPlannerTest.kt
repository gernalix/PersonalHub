package com.gernalix.personalhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MigrationMappingPlannerTest {
    @Test
    fun namespacedIdsPreventCrossAppTableCollisions() {
        val timerSession = MigrationMappingPlanner.namespacedId(
            SourceApp.MULTITIMETRACKER,
            "sessions",
            "1",
        )
        val wordSession = MigrationMappingPlanner.namespacedId(
            SourceApp.WORDPULSE,
            "sessions",
            "1",
        )

        assertNotEquals(timerSession, wordSession)
        assertEquals("multitimetracker:sessions:1", timerSession)
        assertEquals("wordpulse:sessions:1", wordSession)
    }

    @Test
    fun preserveDistinctDoesNotDedupeAmbiguousSourceRecords() {
        val mapping = MigrationMappingPlanner.preserveDistinct(
            sourceApp = SourceApp.SUPERCONTACTS,
            sourceTable = "contacts",
            sourceId = "42",
            entityType = UnifiedEntityType.PERSON,
            notes = "No cross-app identity proof.",
        )

        assertEquals("supercontacts:contacts:42", mapping.newId)
        assertEquals(DedupeDecision.PRESERVE_DISTINCT, mapping.dedupeDecision)
    }

    @Test
    fun inventoryCoversTheFiveInScopeSourceApps() {
        val covered = MigrationInventory.plans.mapTo(linkedSetOf()) { it.sourceApp }

        assertEquals(SourceApp.entries.toSet(), covered)
    }

    @Test
    fun inventoryMapsKnownSharedEntitiesWithoutImplicitDeduplication() {
        val personMapping = MigrationInventory.mappingForRow(
            SourceApp.SUPERCONTACTS,
            "contacts",
            "7",
        )
        val placeMapping = MigrationInventory.mappingForRow(
            SourceApp.LUOGHI,
            "places",
            "place-7",
        )

        assertEquals(UnifiedEntityType.PERSON, personMapping.entityType)
        assertEquals(UnifiedEntityType.PLACE, placeMapping.entityType)
        assertEquals("supercontacts:contacts:7", personMapping.newId)
        assertEquals("luoghi:places:place-7", placeMapping.newId)
        assertEquals(DedupeDecision.PRESERVE_DISTINCT, personMapping.dedupeDecision)
        assertEquals(DedupeDecision.PRESERVE_DISTINCT, placeMapping.dedupeDecision)
    }
}
