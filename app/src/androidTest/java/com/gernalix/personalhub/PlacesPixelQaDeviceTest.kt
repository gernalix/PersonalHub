package com.gernalix.personalhub

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gernalix.luoghi.CHECKIN_BUTTON_TAG
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.capsules.location.FusedLocationCapsule
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.luoghi.ui.visits.formatVisitTimestampForInput
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.SharedTagEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.gernalix.luoghi.R as PlacesR

/** Exercises the real module, ViewModel, Room and shared tags in the isolated QA app. */
@RunWith(AndroidJUnit4::class)
class PlacesPixelQaDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<com.gernalix.luoghi.MainActivity>()

    private fun context(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        check(Build.MANUFACTURER.equals("Google", true) && Build.MODEL == "Pixel 8a")
        check(InstrumentationRegistry.getArguments().getString("physicalQaAuthorized") == "true")
        return context
    }

    private fun waitCount(context: Context, count: Int) {
        val label = context.getString(PlacesR.string.places_count_format, count)
        compose.onNodeWithTag("places-home-list").performScrollToNode(hasTestTag("places-count"))
        try {
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("places-count").assertTextEquals(label)
                    true
                }.getOrDefault(false)
            }
        } catch (failure: AssertionError) {
            val actual = runCatching {
                compose.onNodeWithTag("places-count").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
            }.getOrDefault("unavailable")
            val foreground = androidx.test.uiautomator.UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()).currentPackageName
            val lifecycle = compose.activity.lifecycle.currentState
            throw AssertionError("Places counter expected $label, actual $actual; foreground=$foreground; lifecycle=$lifecycle", failure)
        }
    }

    private fun search(query: String) {
        compose.onNodeWithTag("places-home-list").performScrollToNode(hasTestTag("places-live-search"))
        compose.onNodeWithTag("places-live-search").performTextReplacement(query)
        compose.runOnIdle {
            androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }

    @Test fun liveSearchCombinesNameAddressNotesTagsAndTheSelectedTagFilter() {
        val context = context()
        val repository = PlaceRepository(context)
        val tags = SharedTagEngine(PersonalHubDatabase.get(context))
        val marker = System.currentTimeMillis().toString()
        val tagName = "QA tag $marker"
        val ids = mutableListOf<String>()
        try {
            runBlocking {
                ids += repository.savePlace(null, "QA $marker Caffè", "QA Boulevard", null, null, null,
                    "hidden needle", "QA", tagNames = listOf(tagName))
                ids += repository.savePlace(null, "QA $marker Second", "Other address", null, null, null, null, "QA")
            }
            listOf("$marker caffe", "$marker boulevard", "$marker needle", "$marker tag").forEach { query ->
                search(query)
                waitCount(context, 1)
            }
            search(marker)
            waitCount(context, 2)
            compose.onNodeWithTag("places-home-list").performScrollToNode(hasText(tagName))
            compose.onNodeWithText(tagName).assertIsDisplayed().performClick()
            waitCount(context, 1)
            search("$marker second")
            waitCount(context, 0)
            search(marker)
            waitCount(context, 1)
            val tag = runBlocking { repository.tagsForPlace(ids.first()).single { it.name == tagName } }
            runBlocking { tags.archive(tag.id, true) }
            waitCount(context, 2)
            compose.onNodeWithText(tagName).assertDoesNotExist()
        } finally {
            runBlocking {
                val ownTags = ids.firstOrNull()?.let { repository.tagsForPlace(it) }.orEmpty()
                ids.forEach { repository.deletePlace(it) }
                ownTags.filter { it.name == tagName }.forEach { tags.deleteUnused(it.id) }
            }
        }
    }

    @Test fun exploreUsesTheChosenReferenceAndPreservesItAfterOpeningADetail() {
        val context = context()
        val repository = PlaceRepository(context)
        val dao = PersonalHubDatabase.get(context).placeDao()
        val marker = "QA Explore ${System.currentTimeMillis()}"
        val origin = "$marker Origin"
        val near = "$marker Near"
        val far = "$marker Far"
        val missing = "$marker Missing"
        val ids = mutableListOf<String>()
        try {
            runBlocking {
                ids += repository.savePlace(null, origin, null, 55.6761, 12.5683, 75.0, null, "QA")
                ids += repository.savePlace(null, near, null, 55.6761, 12.5693, 75.0, null, "QA")
                ids += repository.savePlace(null, far, null, 55.6761, 12.5783, 75.0, null, "QA")
                ids += repository.savePlace(null, missing, null, null, null, null, null, "QA")
            }
            val before = dao.listPlacesBlocking()
            compose.onNodeWithTag("places-explore").performScrollTo().performClick()
            compose.onNodeWithTag("explore-reference-search").performTextReplacement(origin)
            compose.runOnIdle {
                androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(origin) and hasClickAction() and !hasSetTextAction())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(origin) and hasClickAction() and !hasSetTextAction()).performClick()
            compose.onNodeWithTag("explore-open-osmand").assertExists()
            val list = compose.onNodeWithTag("places-explore-list")
            list.performScrollToNode(hasText(far))
            val nearY = compose.onNodeWithText(near).fetchSemanticsNode().positionInRoot.y
            val farY = compose.onNodeWithText(far).fetchSemanticsNode().positionInRoot.y
            assertTrue("Nearer place must precede the farther place", nearY < farY)
            list.performScrollToNode(hasText(missing))
            compose.onNodeWithText(context.getString(PlacesR.string.places_explore_missing_heading)).assertExists()
            assertEquals("Choosing a reference must not write place data", before, dao.listPlacesBlocking())
            list.performScrollToNode(hasText(near))
            compose.onNodeWithText(near).performClick()
            compose.onNodeWithContentDescription("hub-detail-places/place/${ids[1]}").assertExists()
            compose.onNodeWithContentDescription(context.getString(PlacesR.string.navigate_back)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("places-explore-list").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("places-explore-list").performScrollToNode(hasTestTag("explore-open-osmand"))
            compose.onNodeWithTag("explore-open-osmand").assertExists()
            compose.onNodeWithTag("explore-reference-search").assertTextContains(origin)
            assertEquals("Returning from detail must preserve all place data", before, dao.listPlacesBlocking())
        } finally {
            runBlocking { ids.forEach { repository.deletePlace(it) } }
        }
    }

    @Test fun whereWasIIsOnHomeAndFindsARecordedVisitAtTheRequestedSecond() {
        val context = context()
        val repository = PlaceRepository(context)
        val db = PersonalHubDatabase.get(context)
        val name = "QA Where ${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val id = runBlocking { repository.savePlace(null, name, null, null, null, null, null, "QA") }
        try {
            runBlocking {
                repository.recordPlaceEvent(id, PlaceEventTypes.CHECK_IN, null, source = "QA", timestamp = now - 240_000)
                repository.recordPlaceEvent(id, PlaceEventTypes.CHECK_OUT, null, source = "QA", timestamp = now - 120_000)
            }
            compose.onNodeWithTag("places-where-was-i").performScrollTo().assertIsDisplayed().performClick()
            compose.onNode(hasSetTextAction() and hasText(context.getString(PlacesR.string.history_timestamp)))
                .performTextReplacement(formatVisitTimestampForInput(now - 180_000))
            val expected = context.getString(PlacesR.string.where_was_i_inside_format, name)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(expected).assertIsDisplayed()
            compose.onNodeWithText(context.getString(PlacesR.string.close)).performClick()
        } finally {
            runBlocking {
                db.placeDao().listEvents().filter { it.placeId == id }.forEach { db.placeDao().deleteEventByUuid(it.eventUuid) }
                db.placeDao().recalculateStatsBaselines()
                repository.deletePlace(id)
            }
        }
    }

    @Test fun overlappingCheckInAllowsCancelAndExplicitChoiceBeyondTheFirstFivePlaces() {
        val context = context()
        val repository = PlaceRepository(context)
        val db = PersonalHubDatabase.get(context)
        val sample = runBlocking { FusedLocationCapsule(context).currentLocation() }
        assertNotNull("A live usable location is required; do not substitute a fake device fix", sample)
        val marker = System.currentTimeMillis().toString()
        val names = (1..8).map { "QA overlap $marker ${it.toString().padStart(2, '0')}" }
        val ids = mutableListOf<String>()
        try {
            runBlocking {
                names.forEach { name -> ids += repository.savePlace(null, name, null,
                    sample!!.latitude, sample.longitude, 5_000.0, null, "QA") }
            }
            val before = db.placeDao().countEventsBlocking()
            compose.onNodeWithTag(CHECKIN_BUTTON_TAG).performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("places-checkin-choices").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, db.placeDao().countEventsBlocking())
            compose.onNodeWithTag("places-checkin-choices").performScrollToNode(hasText(names.last(), substring = true))
            compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
            compose.onNodeWithTag("places-checkin-choices").assertDoesNotExist()
            assertEquals(before, db.placeDao().countEventsBlocking())
            compose.onNodeWithTag(CHECKIN_BUTTON_TAG).performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("places-checkin-choices").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("places-checkin-choices").performScrollToNode(hasText(names.last(), substring = true))
            compose.onNode(hasText(names.last(), substring = true) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { runBlocking { CheckInPolicy.activeVisit(db.placeDao().listEvents())?.placeId == ids.last() } }
            compose.onNodeWithTag("places-checkin-choices").assertDoesNotExist()
            compose.onNodeWithTag("places-where-was-i").performScrollTo().performClick()
            val currentPlace = context.getString(PlacesR.string.where_was_i_inside_format, names.last())
            compose.waitUntil(10_000) { compose.onAllNodesWithText(currentPlace).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(currentPlace).assertIsDisplayed()
            compose.onNodeWithText(context.getString(PlacesR.string.close)).performClick()
            compose.onNodeWithTag(CHECKIN_BUTTON_TAG).performScrollTo().assertTextContains(context.getString(PlacesR.string.checkout_button)).performClick()
            compose.waitUntil(10_000) { runBlocking { CheckInPolicy.activeVisit(db.placeDao().listEvents()) == null } }
        } finally {
            runBlocking {
                db.placeDao().listEvents().filter { it.placeId in ids }.forEach { db.placeDao().deleteEventByUuid(it.eventUuid) }
                db.placeDao().recalculateStatsBaselines()
                ids.forEach { repository.deletePlace(it) }
            }
        }
    }
}
