package com.gernalix.personalhub

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gernalix.luoghi.ADDRESS_FIELD_TAG
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import com.gernalix.luoghi.R as PlacesR

/** Actual module, location source, editor and Room roundtrip on an isolated emulator package. */
class PlacesEditorLocationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<com.gernalix.luoghi.MainActivity>()
    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        check(Build.MODEL.contains("sdk", true) && Build.HARDWARE in listOf("ranchu", "goldfish"))
    }
    private fun hideKeyboard() = compose.runOnIdle {
        androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
    }
    @Test fun currentGpsCreatesAPlaceAndLaterAddressEditingKeepsItsCoordinates() {
        val context = context()
        val repository = PlaceRepository(context)
        val dao = PersonalHubDatabase.get(context).placeDao()
        val name = "PH GPS editor ${System.currentTimeMillis()}"
        var uuid: String? = null
        val eventsBefore = dao.countEventsBlocking()
        try {
            compose.onNodeWithContentDescription(context.getString(PlacesR.string.new_place)).performClick()
            compose.onNode(hasSetTextAction() and hasText(context.getString(PlacesR.string.nickname))).performTextReplacement(name)
            hideKeyboard()
            compose.onNodeWithText(context.getString(PlacesR.string.place_use_current_location)).performScrollTo().performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(context.getString(PlacesR.string.place_current_location_selected)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(PlacesR.string.save)).performClick()
            compose.waitUntil(10_000) { dao.listPlacesBlocking().any { it.nickname == name } }
            val before = dao.listPlacesBlocking().single { it.nickname == name }
            uuid = before.uuid
            assertNotNull(before.lat)
            assertNotNull(before.lon)
            assertEquals("Creating a saved place must not invent a visit", eventsBefore, dao.countEventsBlocking())
            val actions = context.getString(PlacesR.string.place_actions_format, name)
            compose.onNodeWithTag("places-home-list").performScrollToNode(hasContentDescription(actions))
            compose.onNodeWithContentDescription(actions).performClick()
            compose.onNodeWithText(context.getString(PlacesR.string.edit_place)).performClick()
            compose.onNodeWithText(context.getString(PlacesR.string.place_saved_location_preserved)).assertExists()
            compose.onNodeWithTag(ADDRESS_FIELD_TAG).performTextReplacement("Nørrebrogade 12A, København")
            hideKeyboard()
            compose.onNodeWithText(context.getString(PlacesR.string.save)).performClick()
            compose.waitUntil(10_000) { runBlocking { dao.getPlace(before.uuid)?.address == "Nørrebrogade 12A, København" } }
            val after = runBlocking { dao.getPlace(before.uuid) }!!
            assertEquals(before.lat, after.lat)
            assertEquals(before.lon, after.lon)
            assertEquals(eventsBefore, dao.countEventsBlocking())
        } finally {
            runBlocking { uuid?.let { repository.deletePlace(it) } }
        }
    }
}
