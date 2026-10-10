package com.gernalix.personalhub

import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryUndoSafetyTest {
    @Test fun collapsedPageDoesNotStartAnyGitSandboxAndOpenedRowKeepsVerification() = runBlocking {
        val previews = mutableListOf<String>()
        val preview: suspend (String) -> Boolean = { previews += it; it == "safe" }
        repeat(50) { assertFalse(historyUndoSafety(MutationUndoTarget.Git("row-$it"), false, preview)) }
        assertTrue(previews.isEmpty())
        assertTrue(historyUndoSafety(MutationUndoTarget.Activity("local"), false, preview))
        assertFalse(historyUndoSafety(null, true, preview))
        assertTrue(previews.isEmpty())
        assertTrue(historyUndoSafety(MutationUndoTarget.Git("safe"), true, preview))
        assertFalse(historyUndoSafety(MutationUndoTarget.Git("unsafe"), true, preview))
        assertEquals(listOf("safe", "unsafe"), previews)
        assertFalse(historyUndoSafety(MutationUndoTarget.Git("safe"), false, preview))
        assertEquals(2, previews.size)
    }
}
