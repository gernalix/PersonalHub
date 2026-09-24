package com.gernalix.personalhub

import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PersonalHubApplication::class)
class PersonalHubApplicationSinceWhenTest {
    @Test fun validCurrentDatabaseStartupRegistersAllSinceWhenProvenanceProviders() {
        ApplicationProvider.getApplicationContext<PersonalHubApplication>()

        assertEquals("quick_event_entry", HubContextRuntime.adapter("timer", "quick_event_entry").entityKind)
        assertEquals("session", HubContextRuntime.adapter("timer", "session").entityKind)
        assertEquals("transaction", HubContextRuntime.adapter("soldi", "transaction").entityKind)
        assertEquals("place", HubContextRuntime.adapter("places", "place").entityKind)
        assertEquals("substance", HubContextRuntime.adapter("substances", "substance").entityKind)
        assertEquals("tag", HubContextRuntime.adapter("tags", "tag").entityKind)
        assertEquals("counter", HubContextRuntime.adapter("since_when", "counter").entityKind)
    }
}
