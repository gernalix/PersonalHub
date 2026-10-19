package com.gernalix.personalhub

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.performTextInputSelection
import org.junit.Assert.assertEquals
import com.gernalix.personalhub.core.ui.HubSearchField
import org.junit.Rule
import org.junit.Test

class HubSearchFieldDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun delayedFilterAcknowledgementsPreserveMiddleEditsAndCursor() {
        var model by mutableStateOf("alpha beta")
        val submitted = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                HubSearchField(
                    value = model,
                    onValueChange = { submitted += it },
                    modifier = Modifier.testTag("search-input"),
                    label = "Search",
                )
            }
        }
        val field = composeRule.onNodeWithTag("search-input")
        field.performClick()
        field.performTextInputSelection(TextRange(5))
        field.performTextInput("X")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("alphaX beta")))
        field.performTextInput("Y")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(7)))
        // The first filter result arrives after the second keystroke.
        composeRule.runOnIdle { model = "alphaX beta" }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("alphaXY beta")))
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(7)))
        field.performTextInputSelection(TextRange(2))
        composeRule.runOnIdle { model = "alphaXY beta" }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
        composeRule.runOnIdle { assertEquals(listOf("alphaX beta", "alphaXY beta"), submitted) }
        // An explicit saved-query replacement must still work.
        composeRule.runOnIdle { model = "saved query" }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("saved query")))
    }

    @Test
    fun restorationRetainsTheMiddleCursorAndReplaysTheFilterDraft() {
        var model by mutableStateOf("alpha beta")
        val submitted = mutableListOf<String>()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            MaterialTheme {
                HubSearchField(model, { submitted += it }, Modifier.testTag("search-input"))
            }
        }
        val field = composeRule.onNodeWithTag("search-input")
        field.performClick()
        field.performTextInputSelection(TextRange(5))
        field.performTextInput("X")
        field.performTextInputSelection(TextRange(2, 4))
        restoration.emulateSavedInstanceStateRestore()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("alphaX beta")))
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 4)))
        composeRule.runOnIdle { assertEquals("alphaX beta", submitted.last()); model = "alphaX beta" }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 4)))
    }

    @Test
    fun clearButtonEmptiesSearchAndReturnsFocusToField() {
        composeRule.setContent {
            var query by remember { mutableStateOf("pregabalin") }
            MaterialTheme {
                HubSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.testTag("search-input"),
                    label = "Search",
                    clearButtonTestTag = "clear-search",
                )
            }
        }

        composeRule.onNodeWithTag("search-input").performClick()
        composeRule.onNodeWithTag("search-input").performTextInput(" refill")
        composeRule.onNodeWithTag("clear-search").performClick()
        composeRule.onNodeWithTag("search-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        composeRule.onNodeWithTag("search-input").assertIsFocused()
    }
}
