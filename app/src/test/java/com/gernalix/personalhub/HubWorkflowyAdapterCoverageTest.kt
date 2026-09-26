package com.gernalix.personalhub

import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubWorkflowyAdapterCoverageTest {
    @Test fun sharedWorkflowyCapabilityHasEveryRequiredEntityKind() {
        ApplicationProvider.getApplicationContext<PersonalHubApplication>()
        val actual = HubContextRuntime.adapters().map { it.moduleId to it.entityKind }.toSet()
        val required = setOf(
            "timer" to "session",
            "timer" to "quick_event_entry",
            "timer" to "alert",
            "tags" to "tag",
            "places" to "alert",
            "people" to "person",
            "substances" to "substance",
            "substances" to "prescription",
            "soldi" to "transaction",
            "hub" to "context",
            "since_when" to "counter",
        )
        assertTrue("Missing shared Hub adapters: ${required - actual}", actual.containsAll(required))
    }
}
