package com.gernalix.personalhub

import com.gernalix.personalhub.capsules.shortcuts.HubModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubModuleTest {
    @Test
    fun hubContainsExactlyTheSevenFeatureAliases() {
        assertEquals(7, HubModule.entries.size)
        assertEquals(
            setOf("people", "timer", "places", "substances", "wordpulse", "soldi"),
            HubModule.entries.map { it.shortcutPath }.toSet(),
        )
        assertTrue(
            HubModule.entries.all {
                it.shortcutActivityAliasName.startsWith("com.gernalix.personalhub.shortcut.")
            },
        )
    }

    @Test
    fun everyShortcutHasItsOwnAliasComponent() {
        val aliases = HubModule.entries.map { it.shortcutActivityAliasName }
        assertEquals(7, aliases.size)
        assertEquals(aliases.size, aliases.toSet().size)
        assertTrue(aliases.none { it == "com.gernalix.personalhub.MainActivity" })
    }

    @Test
    fun everyPinnedShortcutHasItsOwnStableIdAndIcon() {
        val pinnedIds = HubModule.entries.map { it.pinnedShortcutId }
        assertEquals(7, pinnedIds.size)
        assertEquals(pinnedIds.size, pinnedIds.toSet().size)
        assertTrue(HubModule.entries.all { it.shortcutIconRes != 0 })
    }
}
