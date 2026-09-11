package com.example.multitimetracker.ui.components

import androidx.compose.runtime.saveable.SaverScope
import com.example.multitimetracker.model.SessionUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test fun timerMetadataSaveDoesNotInvokeContextSave() {
        val metadataCalls = mutableListOf<Long>()
        var contextSaveCalls = 0
        saveSessionMetadataOnly(42L, "Focus", setOf(7L)) { id, _, _ -> metadataCalls += id }
        assertEquals(listOf(42L), metadataCalls)
        assertEquals(0, contextSaveCalls)
    }

    private fun session(id: Long): SessionUi = SessionUi(
        id = id,
        title = "Focus",
        startMs = 1_000L,
        endMs = null,
        tagIds = setOf(7L),
        deletedAtMs = null,
    )
}
