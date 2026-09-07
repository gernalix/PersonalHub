package com.example.multitimetracker.widget

import com.example.multitimetracker.core.quickevent.QuickEventTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickEventWidgetPickerTest {
    @Test
    fun longListKeepsDeterministicOrderAndLastItemReachableInFilteredModel() {
        val choices = (1L..60L).map { id ->
            QuickEventWidgetChoice(QuickEventTarget.Template(id), "Event $id", id.toInt())
        }

        val ordered = QuickEventWidgetPicker.filter(choices, "")

        assertEquals(60, ordered.size)
        assertEquals(QuickEventTarget.Template(60L), ordered.last().target)
    }

    @Test
    fun searchMatchesTemplatesAndMacrosCaseInsensitively() {
        val choices = listOf(
            QuickEventWidgetChoice(QuickEventTarget.Template(1L), "Morning Coffee", 1),
            QuickEventWidgetChoice(QuickEventTarget.Macro(2L), "Evening COFFEE", 2),
            QuickEventWidgetChoice(QuickEventTarget.Template(3L), "Walk", 3),
        )

        val filtered = QuickEventWidgetPicker.filter(choices, " coffee ")

        assertEquals(listOf(QuickEventTarget.Template(1L), QuickEventTarget.Macro(2L)), filtered.map { it.target })
    }

    @Test
    fun clearingSearchRestoresCompleteOrderedList() {
        val choices = listOf(
            QuickEventWidgetChoice(QuickEventTarget.Template(2L), "B", 2),
            QuickEventWidgetChoice(QuickEventTarget.Template(1L), "A", 1),
        )

        assertEquals(1, QuickEventWidgetPicker.filter(choices, "A").size)
        assertEquals(listOf(QuickEventTarget.Template(1L), QuickEventTarget.Template(2L)), QuickEventWidgetPicker.filter(choices, "").map { it.target })
    }

    @Test
    fun hiddenSelectionIsClearedRatherThanSaved() {
        val selected = QuickEventTarget.Template(1L)
        val visible = listOf(QuickEventWidgetChoice(QuickEventTarget.Template(2L), "Visible", 2))

        assertNull(QuickEventWidgetPicker.visibleSelection(selected, visible))
    }
}
