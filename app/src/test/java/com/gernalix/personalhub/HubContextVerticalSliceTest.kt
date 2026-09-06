package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.hub.TimerSessionHubAdapter
import com.gernalix.luoghi.capsules.stats.StatsCapsule
import com.gernalix.luoghi.capsules.visits.VisitMapper
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.hub.PlacesHubAdapter
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.supercontacts.app.hub.PeopleHubAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PersonalHubApplication::class)
class HubContextVerticalSliceTest {
    private lateinit var context: Context
    private lateinit var people: PeopleHubAdapter
    private lateinit var places: PlacesHubAdapter
    private lateinit var timer: TimerSessionHubAdapter

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        com.supercontacts.app.data.repository.AppContainer.resetForTests()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
        initialize()
    }

    @After fun tearDown() {
        com.supercontacts.app.data.repository.AppContainer.resetForTests()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @Test fun createRenameEditMoveUnlinkDeleteAndReopenUseOneNaryFact() = runBlocking {
        assertNull(people.create(HubCreateRequest("")))
        assertNull(places.create(HubCreateRequest("")))
        val person = requireNotNull(people.create(HubCreateRequest("Giovanni")))
        val piazza = requireNotNull(places.create(HubCreateRequest("Piazza Savona")))
        assertEquals("75.0", piazza.attributes["radius_m"])
        val otherPlace = requireNotNull(places.create(HubCreateRequest("Altro luogo")))
        val sessionId = DefaultSessionCore(context).insertSession("Pranzo", 1_000L, 8_201_000L, emptySet())

        HubContextRuntime.saveTimerLinks(sessionId, setOf(person.ref.canonicalId), piazza.ref.canonicalId)
        assertEquals(setOf("people", "places"), HubContextRuntime.linked(HubEntityRef("timer", "session", sessionId.toString())).map { it.ref.moduleId }.toSet())
        assertTrue(HubContextRuntime.linked(person.ref).any { it.ref.canonicalId == sessionId.toString() })
        assertTrue(HubContextRuntime.linked(piazza.ref).any { it.ref.canonicalId == sessionId.toString() })

        val database = PersonalHubDatabase.get(context)
        val contact = database.contactsDao().getContactByPublicId(person.ref.canonicalId)!!
        val name = contact.fields.first { it.fieldType == "name" }
        database.contactsDao().updateField(name.copy(value = "Giovanni Nuovo", editedAt = 2_000L))
        val place = database.placeDao().getPlace(piazza.ref.canonicalId)!!
        database.placeDao().upsertPlace(place.copy(nickname = "Piazza Rinominata", updatedAt = 2_000L))
        val labels = HubContextRuntime.linked(HubEntityRef("timer", "session", sessionId.toString())).map { it.label }.toSet()
        assertTrue("Giovanni Nuovo" in labels)
        assertTrue("Piazza Rinominata" in labels)

        var facts = HubContextRuntime.temporalFacts()
        assertEquals(1, facts.size)
        var visits = VisitMapper.map(emptyList(), listOf(database.placeDao().getPlace(piazza.ref.canonicalId)!!), 9_000_000L, facts)
        assertEquals(1, visits.size)
        assertEquals(8_200_000L, visits.single().durationMs)
        assertEquals(listOf("Giovanni Nuovo"), visits.single().relatedPeople)
        assertEquals(8_200_000L, StatsCapsule.calculateFromVisits(listOf(place), visits, null, 9_000_000L).global.totalTrackedTimeMs)

        DefaultSessionCore(context).updateSessionTimes(sessionId, 2_000L, 5_000L)
        assertEquals(3_000L, HubContextRuntime.temporalFacts().single().endMs!! - HubContextRuntime.temporalFacts().single().startMs)
        HubContextRuntime.saveTimerLinks(sessionId, setOf(person.ref.canonicalId), otherPlace.ref.canonicalId)
        facts = HubContextRuntime.temporalFacts()
        assertEquals(otherPlace.ref.canonicalId, facts.single().placeId)
        assertTrue(HubContextRuntime.temporalFactsForPlace(piazza.ref.canonicalId).isEmpty())

        PersonalHubDatabase.closeInstance()
        initialize()
        assertEquals(otherPlace.ref.canonicalId, HubContextRuntime.temporalFacts().single().placeId)
        assertTrue(PersonalHubDatabase.get(context).openHelper.readableDatabase.query("SELECT count(*) FROM hub_sync_pending WHERE table_name LIKE 'hub_context%'").use { it.moveToFirst(); it.getInt(0) } > 0)

        HubContextRuntime.saveTimerLinks(sessionId, setOf(person.ref.canonicalId), null)
        assertTrue(HubContextRuntime.temporalFacts().isEmpty())
        DefaultSessionCore(context).softDeleteSession(sessionId)
        assertTrue(HubContextRuntime.temporalFacts().isEmpty())
    }

    private fun initialize() {
        people = PeopleHubAdapter(context)
        places = PlacesHubAdapter(context)
        timer = TimerSessionHubAdapter(context)
        HubContextRuntime.initialize(context, listOf(people, timer, places))
    }
}
