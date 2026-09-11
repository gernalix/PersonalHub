package com.gernalix.personalhub.core.hubcontext

import androidx.compose.runtime.saveable.SaverScope
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import org.junit.Assert.assertEquals
import org.junit.Test

class HubComposerStateTest {
    @Test fun saverPreservesMultiEntityDraftAcrossActivityRecreation() {
        val anchor = HubEntityRef("people", "person", "giovanni")
        val members = listOf(
            ComposerMember(HubEntitySummary(anchor, "Giovanni"), "people"),
            ComposerMember(HubEntitySummary(HubEntityRef("places", "place", "piazza"), "Piazza Savona"), "place"),
        )
        val state = HubComposerState(anchor, "context-1", members, "uscita", "savona", "Nuovo luogo", "places/place")
        val snapshot = requireNotNull(with(HubComposerState.Saver) { SaverScope { true }.save(state) })
        val restored = requireNotNull(HubComposerState.Saver.restore(snapshot))

        assertEquals(anchor, restored.anchor)
        assertEquals("context-1", restored.editingContextId)
        assertEquals(members, restored.members)
        assertEquals("uscita", restored.typeId)
        assertEquals("savona", restored.query)
        assertEquals("Nuovo luogo", restored.createDraft)
        assertEquals("places/place", restored.selectedKind)
    }

    @Test fun topLevelDraftRestoresWithoutAnchorAndKeepsAutomaticMarker() {
        val member = ComposerMember(HubEntitySummary(HubEntityRef("soldi", "transaction", "tx"), "Coffee"), automatic = true)
        val state = HubComposerState(null, null, listOf(member))
        val snapshot = requireNotNull(with(HubComposerState.Saver) { SaverScope { true }.save(state) })
        val restored = requireNotNull(HubComposerState.Saver.restore(snapshot))
        assertEquals(null, restored.anchor)
        assertEquals(listOf(member), restored.members)
    }

    @Test fun rankingIsDeterministicAndPrioritizesCooccurrenceThenPlaceThenRecency() {
        val co = HubEntitySummary(HubEntityRef("people", "person", "b"), "Co")
        val place = HubEntitySummary(HubEntityRef("soldi", "transaction", "c"), "Place", attributes = mapOf("place_id" to "home"))
        val recent = HubEntitySummary(HubEntityRef("wordpulse", "word_session", "a"), "Recent", attributes = mapOf("start_ms" to "200"))
        assertEquals(listOf("b", "c", "a"), rankComposerCandidates(listOf(recent, place, co), mapOf(co.ref to 2), "home").map { it.ref.canonicalId })
    }
}
