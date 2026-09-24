package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PersonalHubApplication::class)
class PersonalHubApplicationSinceWhenTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        PersonalHubDatabase.get(context).openHelper.writableDatabase
        PersonalHubDatabase.closeInstance()
    }

    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @Test fun validCurrentDatabaseStartupRegistersAllSinceWhenProvenanceProviders() {
        (context.applicationContext as PersonalHubApplication).onCreate()

        assertEquals("quick_event_entry", HubContextRuntime.adapter("timer", "quick_event_entry").entityKind)
        assertEquals("session", HubContextRuntime.adapter("timer", "session").entityKind)
        assertEquals("transaction", HubContextRuntime.adapter("soldi", "transaction").entityKind)
        assertEquals("place", HubContextRuntime.adapter("places", "place").entityKind)
        assertEquals("substance", HubContextRuntime.adapter("substances", "substance").entityKind)
        assertEquals("tag", HubContextRuntime.adapter("tags", "tag").entityKind)
        assertEquals("counter", HubContextRuntime.adapter("since_when", "counter").entityKind)
    }
}
