package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.hub.TimerSessionHubAdapter
import com.gernalix.luoghi.hub.PlacesHubAdapter
import com.gernalix.personalhub.contracts.database.HubCreateRequest
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.supercontacts.app.hub.PeopleHubAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubContextVerticalSliceInstrumentedTest {
    @Test fun canonicalTimerPersonPlaceContextWorksOnAndroid() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa")) { "Hub Context mutation QA must use the isolated .qa package" }
        check(android.os.Build.MODEL.contains("Pixel", ignoreCase = true)) {
            "Hub Context mutation QA must run on the explicitly requested Pixel"
        }
        val people = PeopleHubAdapter(context)
        val places = PlacesHubAdapter(context)
        val timer = TimerSessionHubAdapter(context)
        HubContextRuntime.initialize(context, listOf(people, timer, places))

        val suffix = System.currentTimeMillis().toString()
        val person = requireNotNull(people.create(HubCreateRequest("Hub QA Person $suffix")))
        val place = requireNotNull(places.create(HubCreateRequest("Hub QA Place $suffix")))
        val sessionId = DefaultSessionCore(context).insertSession("Hub QA Session $suffix", 1_000L, 4_000L, emptySet())
        try {
            HubContextRuntime.saveTimerLinks(sessionId, setOf(person.ref.canonicalId), place.ref.canonicalId)
            val sessionRef = HubEntityRef("timer", "session", sessionId.toString())
            assertEquals(setOf("people", "places"), HubContextRuntime.linked(sessionRef).map { it.ref.moduleId }.toSet())
            assertTrue(HubContextRuntime.linked(person.ref).any { it.ref == sessionRef })
            assertTrue(HubContextRuntime.linked(place.ref).any { it.ref == sessionRef })
            assertEquals("75.0", place.attributes["radius_m"])
            assertEquals(3_000L, HubContextRuntime.temporalFactsForPlace(place.ref.canonicalId).single().endMs!! - 1_000L)
        } finally {
            HubContextRuntime.saveTimerLinks(sessionId, emptySet(), null)
            DefaultSessionCore(context).softDeleteSession(sessionId)
        }
    }
}
