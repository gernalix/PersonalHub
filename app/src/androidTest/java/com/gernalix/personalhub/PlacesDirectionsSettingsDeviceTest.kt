package com.gernalix.personalhub

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceAction
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import com.gernalix.luoghi.R as PlacesR

/** Real Android/default-map and actual background-settings UI, isolated emulator only. */
class PlacesDirectionsSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<com.gernalix.luoghi.MainActivity>()
    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        check(Build.MODEL.contains("sdk", true) && Build.HARDWARE in listOf("ranchu", "goldfish"))
    }
    private fun screenshot(context: Context, name: String) {
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(context.getExternalFilesDir(null), "$name.png")))
    }
    private fun directions(context: Context, name: String) {
        val actions = context.getString(PlacesR.string.place_actions_format, name)
        compose.onNodeWithTag("places-home-list").performScrollToNode(hasContentDescription(actions))
        compose.onNodeWithContentDescription(actions).performClick()
        compose.onNodeWithText(context.getString(PlacesR.string.place_directions)).performClick()
    }
    private fun returnToPlaces(context: Context) {
        context.getSystemService(ActivityManager::class.java).appTasks.first {
            it.taskInfo?.baseActivity?.packageName == context.packageName
        }.moveToFront()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("places-home-list").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun directionsUsesTheFullAddressThenCoordinatesWithARealWarningToast() {
        val context = context()
        val repository = PlaceRepository(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val address = "Bernstorffsgade 16, Copenhagen, Denmark"
        val marker = System.currentTimeMillis().toString()
        val ids = mutableListOf<String>()
        Intents.init()
        try {
            runBlocking {
                ids += repository.savePlace(null, "PH address $marker", address, 0.0, 0.0, 75.0, null, "Temporary QA")
                ids += repository.savePlace(null, "PH coordinates $marker", null, 55.6761, 12.5683, 75.0, null, "Temporary QA")
            }
            directions(context, "PH address $marker")
            Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData("geo:0,0?q=${Uri.encode(address)}")))
            assertTrue("Default map app did not open", device.wait(Until.hasObject(By.pkg("net.osmand")), 20000))
            screenshot(context, "directions-address-real-map")
            returnToPlaces(context)
            // Capture Android's actual toast accessibility event while opening the map again.
            val actions = context.getString(PlacesR.string.place_actions_format, "PH coordinates $marker")
            compose.onNodeWithTag("places-home-list").performScrollToNode(hasContentDescription(actions))
            compose.onNodeWithContentDescription(actions).performClick()
            val warning = context.getString(PlacesR.string.place_directions_coordinates)
            val event = InstrumentationRegistry.getInstrumentation().uiAutomation.executeAndWaitForEvent(
                { compose.onNodeWithText(context.getString(PlacesR.string.place_directions)).performClick() },
                { it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && it.text.any { text -> text.toString().contains(warning) } },
                7000,
            )
            assertNotNull("Missing-coordinate warning toast was not observed", event)
            Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData("geo:55.6761,12.5683?q=55.6761,12.5683")))
            assertTrue(device.wait(Until.hasObject(By.pkg("net.osmand")), 15000))
            screenshot(context, "directions-coordinates-real-map")
            returnToPlaces(context)
        } finally {
            Intents.release()
            runBlocking { ids.forEach { repository.deletePlace(it) } }
        }
    }

    @Test fun backgroundSettingsSeparateArrivalDepartureAndPersistChoicesWithoutPermission() {
        val context = context()
        val repository = PlaceRepository(context)
        val dao = PersonalHubDatabase.get(context).placeDao()
        val name = "PH background ${System.currentTimeMillis()}"
        val id = runBlocking { repository.savePlace(null, name, "Temporary QA", 35.0, 139.0, 123.0, null, "Temporary QA") }
        fun option(resource: Int) = compose.onNode(hasText(context.getString(resource)) and isSelectable())
        fun openSettings() {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(context.getString(PlacesR.string.geofence_settings)))
            compose.onNode(hasText(context.getString(PlacesR.string.geofence_settings)) and hasClickAction()).performClick()
        }
        try {
            compose.onNodeWithTag("places-home-list").performScrollToNode(hasText(name))
            compose.onNode(hasText(name) and hasClickAction()).performClick()
            openSettings()
            option(PlacesR.string.geofence_automatic_checkin).assertIsNotEnabled()
            compose.onNode(hasText(context.getString(PlacesR.string.geofence_enabled)) and isToggleable()).performClick()
            compose.onNodeWithText(context.getString(PlacesR.string.geofence_permission_guidance)).assertExists()
            option(PlacesR.string.geofence_automatic_checkin).performScrollTo().performClick().assertIsSelected()
            option(PlacesR.string.geofence_automatic_checkout).performScrollTo().performClick().assertIsSelected()
            screenshot(context, "background-settings-actions")
            compose.onNodeWithText(context.getString(PlacesR.string.save)).performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithText(context.getString(PlacesR.string.geofence_permission_missing)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(PlacesR.string.close)).performClick()
            val saved = runBlocking { dao.geofenceConfig(id) }!!
            assertTrue(saved.enabled && saved.enterEnabled && saved.exitEnabled)
            assertEquals(PlaceGeofenceAction.AUTOMATIC_VISIT.name, saved.enterAction)
            assertEquals(PlaceGeofenceAction.AUTOMATIC_VISIT.name, saved.exitAction)
            openSettings()
            option(PlacesR.string.geofence_automatic_checkin).assertIsSelected()
            option(PlacesR.string.geofence_automatic_checkout).performScrollTo().assertIsSelected()
            compose.onNode(hasText(context.getString(PlacesR.string.geofence_enabled)) and isToggleable()).performScrollTo().performClick()
            option(PlacesR.string.geofence_automatic_checkin).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(PlacesR.string.cancel)).performClick()
        } finally {
            runBlocking { repository.deletePlace(id) }
        }
    }
}
