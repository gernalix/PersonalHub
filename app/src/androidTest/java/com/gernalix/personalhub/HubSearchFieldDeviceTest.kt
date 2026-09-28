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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import com.gernalix.personalhub.core.ui.HubSearchField
import org.junit.Rule
import org.junit.Test

class HubSearchFieldDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

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
