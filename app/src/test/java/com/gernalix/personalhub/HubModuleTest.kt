package com.gernalix.personalhub

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
}
