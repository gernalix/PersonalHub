package com.gernalix.personalhub.core.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class SearchEditBufferTest {
    @Test fun lateAcknowledgementsDoNotRewindTextSelectionOrComposition() {
        val buffer = SearchEditBuffer("alpha beta")
        assertTrue(buffer.edit(TextFieldValue("alphaX beta", TextRange(6))))
        val latest = TextFieldValue("alphaXY beta", TextRange(7), TextRange(5, 7))
        assertTrue(buffer.edit(latest))
        buffer.acceptModel("alphaX beta")
        assertEquals(latest, buffer.value)
        buffer.acceptModel("alphaXY beta")
        assertEquals(latest, buffer.value)
    }

    @Test fun selectionOnlyChangesDoNotEmitAnotherSearch() {
        val buffer = SearchEditBuffer("alpha beta")
        val selected = TextFieldValue("alpha beta", TextRange(2, 5), TextRange(1, 6))
        assertFalse(buffer.edit(selected))
        repeat(10) { buffer.acceptModel("alpha beta") }
        assertEquals(selected, buffer.value)
    }

    @Test fun conflatedAcknowledgementRetiresAllEarlierEdits() {
        val buffer = SearchEditBuffer("")
        listOf("a", "ab", "abc").forEach { buffer.edit(TextFieldValue(it, TextRange(it.length))) }
        buffer.acceptModel("abc")
        buffer.edit(TextFieldValue("abc", TextRange(1)))
        buffer.acceptModel("abc")
        assertEquals(TextRange(1), buffer.value.selection)
        buffer.acceptModel("saved query")
        assertEquals(TextFieldValue("saved query", TextRange(11)), buffer.value)
    }

    @Test fun explicitClearDropsCompositionAndOldPendingEdits() {
        val buffer = SearchEditBuffer("original")
        buffer.edit(TextFieldValue("originalX", TextRange(9), TextRange(8, 9)))
        buffer.acceptModel("")
        assertEquals(TextFieldValue(""), buffer.value)
        assertTrue(buffer.edit(TextFieldValue("next", TextRange(4))))
        buffer.acceptModel("next")
        assertEquals("next", buffer.value.text)
    }
}
