package com.gernalix.luoghi

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.luoghi.capsules.checkin.CheckInCapsule
import com.gernalix.luoghi.capsules.checkin.HistoryMutationResult
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.geofence.*
import com.gernalix.luoghi.data.PlaceGeofenceConfigEntity
import com.gernalix.luoghi.data.PlaceDeleteResult
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.alerts.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.SharedTagEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceCandidateAutomationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val location = LocationSample(55.67, 12.56, 5.0)
    @Before fun setUp() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    private fun db() = PersonalHubDatabase.get(context)
    private fun repository() = PlaceRepository(context)
    private fun capsule() = CheckInCapsule(repository(), PlaceAlertEngine(context, emitTaskerBroadcast = false))
    private suspend fun places(count: Int): List<String> = (1..count).map { index ->
        val id = "candidate-$index"
        db().placeDao().upsertPlace(PlaceEntity(uuid = id, nickname = "Place $index", lat = 55.67, lon = 12.56))
        id
    }
    private suspend fun rule(placeId: String, trigger: AlertTrigger = AlertTrigger.PLACE_BOTH): String =
        PlaceAlertRepository(context).create(PlaceAlertDraft("Alert $placeId", trigger, AlertTargetKind.ENTITY, placeId = placeId))

    @Test fun eightCandidatesFireOnceEachButRecordOnlyChosenVisitAndSurviveDatabaseReopen() = runBlocking {
        val ids = places(8)
        val selected = ids.last()
        val tagEngine = SharedTagEngine(db())
        val tag = tagEngine.getOrCreate(HubTagNamespaces.PLACES, "shared")
        ids.forEach { tagEngine.assign(HubEntityRef("places", "place", it), listOf(tag.id)) }
        val rules = ids.map { rule(it) } + PlaceAlertRepository(context).create(
            PlaceAlertDraft("Shared tag", AlertTrigger.PLACE_BOTH, AlertTargetKind.TAGS, placeTagIds = setOf(tag.id)),
        )
        val checkedIn = capsule().checkIn(selected, location, geofencePlaceUuids = ids + ids)
        assertEquals(1, db().placeDao().listEvents().size)
        assertEquals(selected, db().placeDao().getEvent(checkedIn)!!.placeId)
        assertEquals(listOf(selected) + ids.dropLast(1), repository().geofencePlacesForEvent(checkedIn))
        assertEquals(9, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        rules.forEach { assertNotNull(db().alertDao().getRule(it)!!.lastFiredAt) }
        context.getSystemService(NotificationManager::class.java).cancelAll()
        PersonalHubDatabase.resetForTests() // real file-backed reopen, no in-memory cohort
        val checkedOut = capsule().checkOut(selected, LocationSample(0.0, 0.0, 5.0))
        assertEquals(ids.toSet(), repository().geofencePlacesForEvent(checkedOut).toSet())
        val events = db().placeDao().listEvents()
        assertEquals(2, events.size)
        assertEquals(setOf(selected), events.map { it.placeId }.toSet())
        assertEquals(1, events.map { it.sessionUuid }.distinct().size)
        assertEquals(9, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test fun allTagsMustBelongToOneCandidateAndAnyTagRuleFiresOnlyOnce() = runBlocking {
        val ids = places(2)
        val tags = SharedTagEngine(db())
        val first = tags.getOrCreate(HubTagNamespaces.PLACES, "first")
        val second = tags.getOrCreate(HubTagNamespaces.PLACES, "second")
        tags.assign(HubEntityRef("places", "place", ids[0]), listOf(first.id))
        tags.assign(HubEntityRef("places", "place", ids[1]), listOf(second.id))
        val alerts = PlaceAlertRepository(context)
        val allRule = alerts.create(PlaceAlertDraft("All", AlertTrigger.PLACE_BOTH, AlertTargetKind.TAGS,
            placeTagIds = setOf(first.id, second.id), matchMode = AlertMatchMode.ALL))
        alerts.create(PlaceAlertDraft("Any", AlertTrigger.PLACE_BOTH, AlertTargetKind.TAGS,
            placeTagIds = setOf(first.id, second.id), matchMode = AlertMatchMode.ANY))
        val engine = PlaceAlertEngine(context, emitTaskerBroadcast = false)
        assertEquals(1, engine.onPlaceEvents(ids + ids + "missing", AlertTrigger.PLACE_CHECK_IN, 1000L))
        assertNull(db().alertDao().getRule(allRule)!!.lastFiredAt)
        tags.assign(HubEntityRef("places", "place", ids[1]), listOf(first.id))
        assertEquals(2, engine.onPlaceEvents(ids, AlertTrigger.PLACE_CHECK_OUT, 2000L))
    }

    @Test fun groupMatchingKeepsOneTimeAndCooldownSemantics() = runBlocking {
        val ids = places(2)
        val alerts = PlaceAlertRepository(context)
        val once = alerts.create(PlaceAlertDraft("Once", AlertTrigger.PLACE_BOTH, AlertTargetKind.ENTITY,
            placeId = ids[0], scope = AlertScope.ONE_TIME))
        val cooldown = alerts.create(PlaceAlertDraft("Cooldown", AlertTrigger.PLACE_BOTH, AlertTargetKind.ENTITY,
            placeId = ids[1], cooldownMs = 1000L))
        val engine = PlaceAlertEngine(context, emitTaskerBroadcast = false)
        assertEquals(2, engine.onPlaceEvents(ids + ids, AlertTrigger.PLACE_CHECK_IN, 1000L))
        assertFalse(db().alertDao().getRule(once)!!.enabled)
        assertEquals(0, engine.onPlaceEvents(ids, AlertTrigger.PLACE_CHECK_OUT, 1500L))
        assertEquals(1, engine.onPlaceEvents(ids, AlertTrigger.PLACE_CHECK_OUT, 2000L))
        assertEquals(2000L, db().alertDao().getRule(cooldown)!!.lastFiredAt)
    }

    @Test fun deniedNotificationsDoNotConsumeOneTimeAlert() = runBlocking {
        val selected = places(1).single()
        val id = PlaceAlertRepository(context).create(PlaceAlertDraft("Once", AlertTrigger.PLACE_BOTH,
            AlertTargetKind.ENTITY, placeId = selected, scope = AlertScope.ONE_TIME))
        val engine = PlaceAlertEngine(context, emitTaskerBroadcast = false)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(0, engine.onPlaceEvents(listOf(selected), AlertTrigger.PLACE_CHECK_IN))
        assertTrue(db().alertDao().getRule(id)!!.enabled)
        assertNull(db().alertDao().getRule(id)!!.lastFiredAt)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(1, engine.onPlaceEvents(listOf(selected), AlertTrigger.PLACE_CHECK_IN))
        assertFalse(db().alertDao().getRule(id)!!.enabled)
    }

    @Test fun missingCandidateRollsBackVisitAndAllCohortLinksTogether() = runBlocking {
        val selected = places(1).single()
        val result = runCatching { capsule().checkIn(selected, location, geofencePlaceUuids = listOf(selected, "missing")) }
        assertTrue(result.isFailure)
        assertTrue(db().placeDao().listEvents().isEmpty())
        assertTrue(db().placeDao().listLinksForBackup().isEmpty())
    }

    @Test fun manualCheckoutUsesOriginalGroupAfterReopenAndUndoRedo() = runBlocking {
        val ids = places(2)
        ids.forEach { rule(it, AlertTrigger.PLACE_CHECK_OUT) }
        val eventId = capsule().checkIn(ids[0], location, geofencePlaceUuids = ids)
        val checkedIn = db().placeDao().getEvent(eventId)!!
        assertEquals(HistoryMutationResult.Success, repository().deleteHistorySession(checkedIn.sessionUuid))
        assertEquals(HistoryMutationResult.Success, repository().undoLatestHistoryAction())
        assertEquals(HistoryMutationResult.Success, repository().redoLatestHistoryAction())
        assertTrue(db().placeDao().listEvents().isEmpty())
        assertEquals(HistoryMutationResult.Success, repository().undoLatestHistoryAction())
        PersonalHubDatabase.resetForTests()
        assertEquals(HistoryMutationResult.Success, capsule().manualCheckOut(ids[0], checkedIn.timestamp + 1000L))
        assertEquals(2, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertEquals(2, db().placeDao().listEvents().size)
    }

    @Test fun linkedCandidateDeletionArchivesItAndRetainsOriginalExitRules() = runBlocking {
        val ids = places(2)
        rule(ids[1], AlertTrigger.PLACE_CHECK_OUT)
        capsule().checkIn(ids[0], location, geofencePlaceUuids = ids)
        assertEquals(PlaceDeleteResult.ArchivedBecauseReferenced, repository().deletePlace(ids[1]))
        assertTrue(db().placeDao().getPlace(ids[1])!!.archived)
        capsule().checkOut(ids[0], null)
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertEquals(setOf(ids[0]), db().placeDao().listEvents().map { it.placeId }.toSet())
    }
    @Test fun laterSoloVisitDoesNotInheritPreviousCandidatesOrOtherOwnerLinks() = runBlocking {
        val ids = places(3)
        repository().createLink(ids[2], "Finance", "transaction", "unrelated")
        capsule().checkIn(ids[0], location, geofencePlaceUuids = ids.take(2))
        capsule().checkOut(ids[0], null)
        val solo = capsule().checkIn(ids[0], location)
        assertEquals(listOf(ids[0]), repository().geofencePlacesForEvent(solo))
        assertEquals(listOf(ids[0]), repository().geofencePlacesForActiveVisit(ids[0]))
    }

    private suspend fun automaticConfigs(ids: List<String>) {
        ids.forEach { db().placeDao().upsertGeofenceConfig(PlaceGeofenceConfigEntity(
            placeUuid = it, enabled = true, enterEnabled = true, exitEnabled = true,
            enterAction = PlaceGeofenceAction.AUTOMATIC_VISIT.name,
            exitAction = PlaceGeofenceAction.AUTOMATIC_VISIT.name,
        )) }
    }

    @Test fun platformOverlapNotifiesEveryCandidateAndOffersARealChoiceWithoutInventingVisits() = runBlocking {
        val ids = places(2)
        automaticConfigs(ids)
        val results = PlaceGeofenceReceiver.handleTransitions(context, ids + ids, PlaceGeofenceTransition.ENTER, 10_000L)
        assertEquals(ids.toSet(), results.keys)
        assertTrue(results.values.all { it == PlaceGeofenceResult.Success })
        assertTrue(db().placeDao().listEvents().isEmpty())
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(3, notifications.size)
        val choice = notifications.single { it.id == 43_190 }.notification
        val intent = shadowOf(choice.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.dataString!!.contains("places"))
        assertTrue(PlaceGeofenceReceiver.handleTransitions(context, ids, PlaceGeofenceTransition.ENTER, 10_100L)
            .values.all { it == PlaceGeofenceResult.DuplicateTransition })
        assertEquals(2, db().placeDao().listGeofenceTransitionLogForBackup().size)
    }

    @Test fun platformOverlapExitClosesOnlyTheVisitActuallyChosen() = runBlocking {
        val ids = places(2)
        automaticConfigs(ids)
        capsule().checkIn(ids[1], location, geofencePlaceUuids = ids)
        val results = PlaceGeofenceReceiver.handleTransitions(context, ids, PlaceGeofenceTransition.EXIT, System.currentTimeMillis())
        assertTrue(results.values.all { it == PlaceGeofenceResult.Success })
        val events = db().placeDao().listEvents()
        assertEquals(2, events.size)
        assertEquals(setOf(ids[1]), events.map { it.placeId }.toSet())
        assertEquals(setOf(PlaceEventTypes.CHECK_IN, PlaceEventTypes.CHECK_OUT), events.map { it.eventType }.toSet())
    }

    @Test fun rejectedAutomaticArrivalCanRetryInTheSameDedupeWindow() = runBlocking {
        val ids = places(2)
        automaticConfigs(listOf(ids[0]))
        assertEquals(HistoryMutationResult.Success, repository().recordManualVisit(ids[1], 9000L, 15000L))
        assertTrue(PlaceGeofenceReceiver.handleTransition(context, ids[0], PlaceGeofenceTransition.ENTER, 10_000L)
            is PlaceGeofenceResult.VisitRejected)
        assertTrue(db().placeDao().listGeofenceTransitionLogForBackup().isEmpty())
        val conflictSession = db().placeDao().listEvents().first().sessionUuid
        assertEquals(HistoryMutationResult.Success, repository().deleteHistorySession(conflictSession))
        assertEquals(PlaceGeofenceResult.Success,
            PlaceGeofenceReceiver.handleTransition(context, ids[0], PlaceGeofenceTransition.ENTER, 10_100L))
    }

    @Test fun backgroundDetectionRequiresPreciseAsWellAsBackgroundLocation() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertFalse(PlaceGeofenceRegistrar.hasBackgroundLocationPermission(context))
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(PlaceGeofenceRegistrar.hasBackgroundLocationPermission(context))
    }

    @Test fun archivedOrUnlocatedAreasCannotMakeAValidSingleArrivalAmbiguous() = runBlocking {
        val ids = places(3)
        automaticConfigs(ids)
        db().placeDao().setPlaceArchived(ids[1], true, 1L)
        db().placeDao().upsertPlace(db().placeDao().getPlace(ids[2])!!.copy(lat = null, lon = null))
        val result = PlaceGeofenceReceiver.handleTransitions(context, ids, PlaceGeofenceTransition.ENTER, 10_000L)
        assertEquals(mapOf(ids[0] to PlaceGeofenceResult.Success), result)
        assertEquals(setOf(ids[0]), db().placeDao().listEvents().map { it.placeId }.toSet())
        assertEquals(PlaceGeofenceResult.PlaceMissing,
            PlaceGeofenceReceiver.handleTransition(context, ids[1], PlaceGeofenceTransition.ENTER, 10_000L))
        assertEquals(PlaceGeofenceResult.CoordinatesMissing,
            PlaceGeofenceReceiver.handleTransition(context, ids[2], PlaceGeofenceTransition.ENTER, 10_000L))
    }

}
