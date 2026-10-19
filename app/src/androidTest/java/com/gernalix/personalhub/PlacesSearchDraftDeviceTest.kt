package com.gernalix.personalhub

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.gernalix.luoghi.HomeUiState
import com.gernalix.luoghi.ui.home.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlacesSearchDraftDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchKeepsEditsWhileDerivedResultsAreDelayedAndAfterStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val submitted = mutableListOf<String>()
        restoration.setContent {
            MaterialTheme {
                // Deliberately retain the old derived state, as a slow Room/stats
                // collector does. User input must not wait for that round trip.
                HomeScreen(
                    state = HomeUiState(),
                    onCheckAction = {}, onAmbiguousCheckIn = {},
                    onCancelAmbiguousCheckIn = {}, onOpenPlace = {},
                    onEditPlace = {}, onPlaceVisits = {}, onPlaceMap = {},
                    onDeletePlace = {}, onSortPlaces = { _, _ -> },
                    onPlaceQueryChange = { submitted += it },
                    onRefreshLocation = {}, onToggleTagFilter = {},
                    onExplore = {}, onGlobalMap = {}, onGlobalStats = {},
                    onHistorySearch = {}, onOpenVisits = {}, onNewPlace = {},
                )
            }
        }
        val query = "caffe boulevard needle tag"
        fun field() = compose.onNodeWithTag("places-live-search")
        fun showField() = compose.onNodeWithTag("places-home-list")
            .performScrollToNode(hasTestTag("places-live-search"))
        showField()
        field().performTextReplacement(query)
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(query)))
        compose.runOnIdle { assertEquals(query, submitted.last()) }
        // Force lazy content to leave/re-enter the visible viewport.
        compose.onNodeWithTag("places-home-list").performScrollToIndex(0)
        showField()
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(query)))
        restoration.emulateSavedInstanceStateRestore()
        showField()
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(query)))
        compose.runOnIdle { assertEquals(query, submitted.last()) }
        field().performTextReplacement("second")
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("second")))
        compose.runOnIdle { assertEquals("second", submitted.last()) }
    }
}
