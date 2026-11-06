package com.example.multitimetracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.example.multitimetracker.ui.components.RunningSessionRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RunningSessionActionsRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun gesturesReadLatestCallbacksAndRemainUsableAfterAnOwnerLeavesTheRowVisible() {
        val revision = mutableIntStateOf(0)
        val edits = mutableListOf<Int>()
        val deletes = mutableListOf<Int>()
        compose.setContent {
            val current = revision.intValue
            MaterialTheme { Surface {
                RunningSessionRow(972L, "Session", emptyList(), "1m", Color.Black,
                    onStop = {}, onEdit = { edits += current }, onDelete = { deletes += current })
            } }
        }
        fun swipe(right: Boolean) {
            compose.onNodeWithTag("running_session_972").performTouchInput {
                if (right) swipeRight() else swipeLeft()
            }
            compose.waitForIdle()
        }
        compose.runOnIdle { revision.intValue = 1 }
        swipe(true)
        compose.runOnIdle { assertEquals(listOf(1), edits); revision.intValue = 2 }
        swipe(true)
        compose.runOnIdle { assertEquals(listOf(1, 2), edits) }
        swipe(false)
        compose.runOnIdle { assertEquals(listOf(2), deletes); revision.intValue = 3 }
        swipe(false)
        compose.runOnIdle { assertEquals(listOf(2, 3), deletes) }
    }
}
