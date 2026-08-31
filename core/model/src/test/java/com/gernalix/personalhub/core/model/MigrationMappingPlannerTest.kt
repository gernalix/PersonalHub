package com.gernalix.personalhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun verifierPassesWhenEverySourceRecordHasOneMapping() {
        val sourceRows = listOf(
            SourceTableRows(SourceApp.SUPERCONTACTS, "contacts", listOf("1", "2")),
            SourceTableRows(SourceApp.LUOGHI, "places", listOf("home")),
        )
        val mappings = MigrationVerifier.mappingsFor(sourceRows)

        val report = MigrationVerifier.verify(sourceRows, mappings)

        assertTrue(report.passed)
        assertEquals(3, report.sourceCount)
        assertEquals(3, report.mappingCount)
    }

    @Test
    fun verifierRejectsMissingAndUnexpectedMappings() {
        val sourceRows = listOf(
            SourceTableRows(SourceApp.SOSTANZE, "substances", listOf("1", "2")),
        )
        val mappings = listOf(
            MigrationInventory.mappingForRow(SourceApp.SOSTANZE, "substances", "1"),
            MigrationInventory.mappingForRow(SourceApp.SOSTANZE, "substances", "3"),
        )

        val report = MigrationVerifier.verify(sourceRows, mappings)
        val audit = report.tableAudits.single()

        assertFalse(report.passed)
        assertEquals(setOf("2"), audit.missingSourceIds)
        assertEquals(setOf("3"), audit.unexpectedSourceIds)
    }

    @Test
    fun verifierRejectsDuplicateSourceMappingsAndTargetCollisions() {
        val sourceRows = listOf(
            SourceTableRows(SourceApp.WORDPULSE, "sessions", listOf("session-1")),
        )
        val duplicateMappings = listOf(
            MigrationInventory.mappingForRow(SourceApp.WORDPULSE, "sessions", "session-1"),
            MigrationInventory.mappingForRow(SourceApp.WORDPULSE, "sessions", "session-1"),
        )

        val report = MigrationVerifier.verify(sourceRows, duplicateMappings)

        assertFalse(report.passed)
        assertEquals(setOf("session-1"), report.tableAudits.single().duplicateSourceIds)
        assertEquals(setOf("wordpulse:sessions:session-1"), report.duplicateTargetIds)
    }

    @Test
    fun verifierAllowsTargetReuseOnlyForProvenIdentityDeduplication() {
        val sourceRows = listOf(
            SourceTableRows(SourceApp.SUPERCONTACTS, "contacts", listOf("1")),
            SourceTableRows(SourceApp.SUPERCONTACTS, "contact_fields", listOf("field-1")),
        )
        val mappings = listOf(
            MigrationMapping(
                sourceApp = SourceApp.SUPERCONTACTS,
                sourceTable = "contacts",
                sourceId = "1",
                entityType = UnifiedEntityType.PERSON,
                newId = "shared:entity:1",
                dedupeDecision = DedupeDecision.PROVEN_IDENTITY,
            ),
            MigrationMapping(
                sourceApp = SourceApp.SUPERCONTACTS,
                sourceTable = "contact_fields",
                sourceId = "field-1",
                entityType = UnifiedEntityType.PERSON,
                newId = "shared:entity:1",
                dedupeDecision = DedupeDecision.PROVEN_IDENTITY,
            ),
        )

        val report = MigrationVerifier.verify(sourceRows, mappings)

        assertTrue(report.passed)
        assertEquals(emptySet<String>(), report.duplicateTargetIds)
    }
}
