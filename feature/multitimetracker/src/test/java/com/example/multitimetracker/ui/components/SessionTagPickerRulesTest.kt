package com.example.multitimetracker.ui.components

import com.example.multitimetracker.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTagPickerRulesTest {
    @Test fun broaderMatchesDoNotSuppressExactNameCreation() {
        val tags = listOf(tag(1, "Work Deep"), tag(2, "Home"))

        assertTrue(SessionTagPickerRules.canCreateExactName("Work", tags))
    }

    @Test fun caseAndTrimExactMatchSuppressesCreation() {
        val tags = listOf(tag(1, "  Work  "))

        assertFalse(SessionTagPickerRules.canCreateExactName(" work ", tags))
    }

    @Test fun searchMatchesCaseInsensitiveSubstringAndClearRestoresAll() {
        val tags = listOf(tag(1, "Work Deep"), tag(2, "Home"), tag(3, "Admin"))

        assertEquals(listOf(1L), SessionTagPickerRules.filterByQuery(tags, " deep ").map { it.id })
        assertEquals(listOf(1L, 2L, 3L), SessionTagPickerRules.filterByQuery(tags, "   ").map { it.id })
    }

    @Test fun selectedChipsRemainIndependentFromSearchQuery() {
        val tags = listOf(tag(1, "Work"), tag(2, "Home"))
        val selectedIds = setOf(2L)

        assertEquals(listOf(2L), SessionTagPickerRules.selectedTags(tags, selectedIds).map { it.id })
        assertEquals(emptyList<Long>(), SessionTagPickerRules.filterByQuery(tags, "zzz").map { it.id })
        assertEquals(listOf(2L), SessionTagPickerRules.selectedTags(tags, selectedIds).map { it.id })
    }

    private fun tag(id: Long, name: String): Tag = Tag(
        id = id,
        name = name,
        activeChildrenCount = 0,
        totalMs = 0L,
        lastStartedAtMs = null
    )
}
