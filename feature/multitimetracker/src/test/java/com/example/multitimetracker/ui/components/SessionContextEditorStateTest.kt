package com.example.multitimetracker.ui.components

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionContextEditorStateTest {
    @Test fun saverPreservesUnsavedContextSelectionsAndInlineDrafts() {
        val state = SessionContextEditorState(setOf("person-1", "person-2"), "place-1", "Nuova persona", "Nuovo luogo")
        val snapshot = requireNotNull(with(SessionContextEditorState.Saver) { SaverScope { true }.save(state) })
        val restored = requireNotNull(SessionContextEditorState.Saver.restore(snapshot))

        assertEquals(setOf("person-1", "person-2"), restored.peopleIds)
        assertEquals("place-1", restored.placeId)
        assertEquals("Nuova persona", restored.personDraft)
        assertEquals("Nuovo luogo", restored.placeDraft)
    }
}
