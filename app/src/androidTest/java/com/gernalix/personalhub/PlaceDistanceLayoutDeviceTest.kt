package com.gernalix.personalhub

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.ui.explore.ExplorePlacesScreen
import com.gernalix.luoghi.ui.places.PlaceListItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Emulator-only layout evidence with long labels, narrow rows and large text. */
class PlaceDistanceLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Before fun requireIsolatedEmulator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        check(Build.MODEL.contains("sdk", true) && Build.HARDWARE in listOf("ranchu", "goldfish"))
    }
    private fun verifyWholeText(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertFalse("Distance text is clipped or ellipsized", layouts.single().hasVisualOverflow)
        assertTrue(layouts.single().layoutInput.text.text.isNotBlank())
    }
    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(context.getExternalFilesDir(null), "$name.png")))
    }
    private fun renderRow(active: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme {
                    Box(Modifier.safeDrawingPadding().width(220.dp)) {
                        PlaceListItem(
                            PlaceListUiModel(PlaceEntity(uuid = "layout", nickname = "A very long saved place name with a long street address",
                                address = "Long Boulevard 123A, third floor, Copenhagen"),
                                totalTimeMs = 123456789012L, visitCount = 123456, lastVisitAt = null,
                                activeStartedAt = if (active) System.currentTimeMillis() - 10000L else null,
                                distanceMeters = 12900L),
                            onOpen = {}, onEdit = {}, onHistory = {}, onMap = {}, onDelete = {}, showDistance = true,
                        )
                    }
                }
            }
        }
        verifyWholeText(compose.onNodeWithTag("place-distance", useUnmergedTree = true))
        screenshot(if (active) "active-place-distance-large-text" else "place-distance-large-text")
    }
    @Test fun currentVisitDistanceRemainsFullyVisibleWithLargeText() = renderRow(true)
    @Test fun inactivePlaceDistanceIsSeparateFromLongVisitMetadata() = renderRow(false)
    @Test fun exploreShowsADistanceOrExplicitUnavailableValueForEveryPlace() {
        val places = listOf(
            PlaceEntity(uuid = "reference", nickname = "PH Reference", lat = 55.67, lon = 12.56),
            PlaceEntity(uuid = "near", nickname = "A very long nearby place name that wraps across several lines", lat = 55.671, lon = 12.56),
            PlaceEntity(uuid = "far", nickname = "A very long distant place name that wraps across several lines", lat = 55.8, lon = 12.6),
            PlaceEntity(uuid = "missing", nickname = "Place without saved coordinates"),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.7f)) {
                MaterialTheme { Box(Modifier.safeDrawingPadding().width(240.dp)) { ExplorePlacesScreen(places, {}, {}) } }
            }
        }
        compose.onNodeWithTag("explore-reference-search").performTextInput("PH Reference")
        compose.onNode(hasText("PH Reference") and hasClickAction() and !hasSetTextAction()).performClick()
        places.forEach { place ->
            val tag = "explore-place-distance-${place.uuid}"
            compose.onNodeWithTag("places-explore-list").performScrollToNode(hasText(place.nickname))
            verifyWholeText(compose.onNodeWithTag(tag, useUnmergedTree = true))
        }
        screenshot("explore-distance-large-text")
    }
}
