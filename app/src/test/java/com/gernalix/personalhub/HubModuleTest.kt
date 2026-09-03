package com.gernalix.personalhub

import com.gernalix.personalhub.capsules.shortcuts.HubModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubModuleTest {
    @Test
    fun hubContainsExactlyTheFiveFeatureApps() {
        assertEquals(5, HubModule.entries.size)
        val activityNames = HubModule.entries.map { it.activityClassName }

        assertTrue("com.supercontacts.app.MainActivity" in activityNames)
        assertTrue("com.example.multitimetracker.MainActivity" in activityNames)
        assertTrue("com.gernalix.luoghi.MainActivity" in activityNames)
        assertTrue("com.gernalix.sostanze.MainActivity" in activityNames)
        assertTrue("com.wordpulse.app.MainActivity" in activityNames)
        assertEquals(activityNames.toSet().size, activityNames.size)
    }

    @Test
    fun everyShortcutHasItsOwnAliasComponent() {
        val aliases = HubModule.entries.map { it.shortcutActivityAliasName }
        assertEquals(5, aliases.size)
        assertEquals(aliases.size, aliases.toSet().size)
        assertTrue(aliases.all { it.startsWith("com.gernalix.personalhub.shortcut.") })
    }

    @Test
    fun everyPinnedShortcutHasItsOwnStableIdAndIcon() {
        val pinnedIds = HubModule.entries.map { it.pinnedShortcutId }
        assertEquals(5, pinnedIds.size)
        assertEquals(pinnedIds.size, pinnedIds.toSet().size)
        assertTrue(HubModule.entries.all { it.shortcutIconRes != 0 })
    }

    @Test
    fun directShortcutAliasesDoNotRouteThroughPersonalHubHome() {
        assertTrue(HubModule.entries.none { it.shortcutActivityAliasName == "com.gernalix.personalhub.MainActivity" })
        assertTrue(HubModule.entries.none { it.activityClassName == "com.gernalix.personalhub.MainActivity" })
    }
}
