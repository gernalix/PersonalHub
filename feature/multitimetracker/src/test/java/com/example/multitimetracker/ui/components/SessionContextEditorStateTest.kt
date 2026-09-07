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

    @Test fun newSessionContextSaveUsesCreatedSessionIdOnly() = runBlocking {
        val savedSessionIds = mutableListOf<Long>()
        var createCalls = 0

        fun createNewSession(onCreated: (SessionUi) -> Unit) {
            createCalls += 1
            assertTrue(savedSessionIds.isEmpty())
            onCreated(session(id = 42L))
        }

        createNewSession { createdSession ->
            runBlocking {
                saveContextForCreatedTimerSession(createdSession) { sessionId ->
                    savedSessionIds += sessionId
                }
            }
        }

        assertEquals(1, createCalls)
        assertEquals(listOf(42L), savedSessionIds)
    }

    @Test fun draftSessionIdCannotBeUsedForContextSave() = runBlocking {
        try {
            saveContextForCreatedTimerSession(session(id = -1L)) {
                fail("Context save must not run for a draft Timer session")
            }
            fail("Draft Timer session should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Timer session must be persisted before saving Context", expected.message)
        }
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
