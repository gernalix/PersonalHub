package com.gernalix.personalhub

import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.core.hubcontext.HubTemporalKind
import com.gernalix.personalhub.core.hubcontext.HubTemporalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubTemporalSearchScreenTest {
    @Test
    fun groupsByDeterministicSectionsWithoutModuleChips() {
        val entries = buildTemporalEntries(
            listOf(
                record("timer", "session", "1", 20, "Timer B"),
                record("places", "visit", "2", 30, "Home"),
                record("timer", "session", "3", 40, "Timer A"),
            ),
        )

        val grouped = groupedTemporalEntries(entries)

        assertEquals(listOf("Places", "Timer"), grouped.map { it.first })
        assertEquals(listOf("Timer A", "Timer B"), grouped.single { it.first == "Timer" }.second.map { it.title })
        assertFalse(entries.any { it.title == "timer" || it.subtitle?.contains("timer") == true })
    }

    @Test
    fun keepsPeopleBoundedToProvidedContextLinks() {
        val person = HubEntitySummary(HubEntityRef("people", "person", "p1"), "Ada")
        val entries = buildTemporalEntries(listOf(record("timer", "session", "1", 20, "Timer")), listOf(person))

        val people = groupedTemporalEntries(entries).single { it.first == "People" }.second

        assertEquals(listOf("Ada"), people.map { it.title })
        assertEquals(listOf(person.ref), people.single().refs)
    }

    @Test
    fun aggregatesOnlyValidWordPulseFatigueScoresAndKeepsCanonicalRefs() {
        val entries = buildTemporalEntries(
            listOf(
                wordPulse("w1", 10, "20"),
                wordPulse("w2", 20, "100"),
                wordPulse("w3", 30, "101"),
                wordPulse("w4", 40, null),
            ),
        )

        val wordPulse = entries.single()

        assertEquals("Average fatigue: 60/100", wordPulse.title)
        assertEquals(listOf("w1", "w2", "w3", "w4"), wordPulse.refs.map { it.canonicalId })
        assertTrue(wordPulse.selectable)
    }

    @Test
    fun wordPulseShowsUnavailableWhenNoCanonicalFatigueScoresExist() {
        val entries = buildTemporalEntries(listOf(wordPulse("w1", 10, null)))

        assertEquals("Average fatigue: unavailable", entries.single().title)
    }

    @Test
    fun selectionEntriesExposeUnderlyingRefsForSubsetEpisodeSave() {
        val entries = buildTemporalEntries(listOf(record("soldi", "transaction", "tx1", 20, "Coffee")))

        assertEquals(listOf(HubEntityRef("soldi", "transaction", "tx1")), entries.single().refs)
        assertTrue(entries.single().selectable)
    }

    @Test
    fun recordWithoutCanonicalRefRemainsVisibleButCannotBeSaved() {
        val entry = buildTemporalEntries(
            listOf(
                HubTemporalRecord(
                    moduleId = "timer",
                    source = "derived_summary",
                    stableId = "synthetic",
                    kind = HubTemporalKind.POINT,
                    startMs = 20,
                    title = "Derived",
                    entityRef = null,
                ),
            ),
        ).single()

        assertEquals("Derived", entry.title)
        assertTrue(entry.refs.isEmpty())
        assertFalse(entry.selectable)
    }

    private fun record(moduleId: String, source: String, id: String, startMs: Long, title: String) = HubTemporalRecord(
        moduleId = moduleId,
        source = source,
        stableId = id,
        kind = HubTemporalKind.POINT,
        startMs = startMs,
        title = title,
        entityRef = HubEntityRef(moduleId, source, id),
    )

    private fun wordPulse(id: String, startMs: Long, fatigueScore: String?) = HubTemporalRecord(
        moduleId = "wordpulse",
        source = "word_session",
        stableId = id,
        kind = HubTemporalKind.INTERVAL,
        startMs = startMs,
        endMs = startMs + 1,
        title = "ignored",
        entityRef = HubEntityRef("wordpulse", "word_session", id),
        attributes = fatigueScore?.let { mapOf("fatigueScore" to it) }.orEmpty(),
    )
}
