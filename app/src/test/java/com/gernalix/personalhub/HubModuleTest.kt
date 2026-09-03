package com.gernalix.personalhub

import android.content.Intent
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
    fun everyShortcutHasItsOwnDeepLink() {
        val shortcutUris = HubModule.entries.map { it.shortcutUri }
        assertEquals(5, shortcutUris.size)
        assertEquals(shortcutUris.size, shortcutUris.toSet().size)
    }

    @Test
    fun everyShortcutResolvesOnlyItsOwnFeature() {
        HubModule.entries.forEach { module ->
            assertEquals(
                module,
                HubModule.fromShortcutParts(
                    action = Intent.ACTION_VIEW,
                    scheme = "personalhub",
                    host = "module",
                    pathSegments = listOf(module.shortcutPath),
                ),
            )
        }
        assertEquals(
            null,
            HubModule.fromShortcutParts(Intent.ACTION_VIEW, "personalhub", "module", listOf("migration")),
        )
    }
}
