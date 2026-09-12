package com.example.multitimetracker.capsules.now.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.now.controller.NowCapsuleViewModel
import com.example.multitimetracker.capsules.now.state.NowUiState
import com.example.multitimetracker.capsules.system.NowCapsuleAccess
import com.example.multitimetracker.model.HomeLoadState
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickStartTagLauncherInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val alpha = tag(id = 1L, name = "Alpha")
    private val beta = tag(id = 2L, name = "Beta")
    private val gamma = tag(id = 3L, name = "Gamma")

    @Test
    fun shortTapStartsExactlyOneSingleTagSession() {
        val starts = mutableListOf<List<Long>>()
        setLauncher(starts)

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(listOf(alpha.id)), starts)
        }
    }

    @Test
    fun longPressEntersMultiSelectWithoutStartingSession() {
        val starts = mutableListOf<List<Long>>()
        setLauncher(starts)

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).performTouchInput { longClick() }

        composeRule.onNodeWithTag("quick_start_confirm").assertExists()
        composeRule.onNodeWithTag(tagTestTag(alpha.id)).assertIsSelected()
        composeRule.runOnIdle {
            assertTrue(starts.isEmpty())
        }
    }

    @Test
    fun multiSelectStartsOneSessionContainingAllSelectedTags() {
        val starts = mutableListOf<List<Long>>()
        setLauncher(starts)

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(tagTestTag(beta.id)).performClick()

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).assertIsSelected()
        composeRule.onNodeWithTag(tagTestTag(beta.id)).assertIsSelected()
        composeRule.onNodeWithTag("quick_start_confirm").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(listOf(alpha.id, beta.id)), starts)
        }
        composeRule.onNodeWithTag("quick_start_confirm").assertDoesNotExist()
    }

    @Test
    fun searchFiltersTagsAndPreservesHiddenMultiSelection() {
        val starts = mutableListOf<List<Long>>()
        setLauncher(starts)

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).performTouchInput { longClick() }
        composeRule.onNodeWithTag("quick_start_search").performTextInput("Beta")

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(tagTestTag(beta.id)).assertExists().performClick()
        composeRule.onNodeWithTag(tagTestTag(gamma.id)).assertDoesNotExist()

        composeRule.onNodeWithTag("quick_start_search").performTextClearance()

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).assertIsSelected()
        composeRule.onNodeWithTag(tagTestTag(beta.id)).assertIsSelected()
        composeRule.onNodeWithTag(tagTestTag(gamma.id)).assertExists()
        composeRule.onNodeWithTag("quick_start_confirm").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(listOf(alpha.id, beta.id)), starts)
        }
    }

    @Test
    fun undoDeletesExactlyTheSessionCreatedByQuickStart() {
        val createdIds = mutableListOf<Long>()
        val deletedIds = mutableListOf<Long>()
        val access = FakeNowAccess(
            MutableStateFlow(
                nowState(
                    tags = listOf(alpha, beta),
                    nowMs = 1_000_000L,
                )
            )
        )
        val capsule = NowCapsuleViewModel(
            access = access,
            createNewSessionOverride = { title, startMs, tagIds, onCreated ->
                val created = SessionUi(
                    id = 91L,
                    title = title,
                    startMs = startMs,
                    endMs = null,
                    tagIds = tagIds,
                    deletedAtMs = null,
                )
                createdIds += created.id
                onCreated(created)
            },
            deleteSessionOverride = { deletedIds += it },
        )

        composeRule.setContent {
            MaterialTheme {
                NowScreen(
                    modifier = Modifier.fillMaxSize(),
                    capsule = capsule,
                    onOpenTag = {},
                    onOpenStatistics = {},
                    onOpenDiagnostics = {},
                    showSeconds = false,
                    onShowSecondsChange = {},
                    hideHoursIfZero = true,
                    onHideHoursIfZeroChange = {},
                    keepScreenOn = false,
                    onKeepScreenOnChange = {},
                )
            }
        }

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).performClick()

        val undoLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.undo)
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            composeRule.onAllNodesWithText(undoLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(undoLabel)[0].performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(91L), createdIds)
            assertEquals(listOf(91L), deletedIds)
        }
    }

    private fun setLauncher(starts: MutableList<List<Long>>) {
        composeRule.setContent {
            MaterialTheme {
                QuickStartTagLauncher(
                    tags = listOf(alpha, beta, gamma),
                    enabled = true,
                    onStartSession = { selected -> starts += selected.map { it.id } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun nowState(tags: List<Tag>, nowMs: Long): NowUiState =
        NowUiState(
            tags = tags,
            chronologySessions = emptyList(),
            runningSessions = emptyList(),
            activeTagTotalsMsByTagId = emptyMap(),
            runningMinStartByTagId = emptyMap(),
            tagLastUsedMsByTagId = emptyMap(),
            tagParentsByChild = emptyMap(),
            nowMs = nowMs,
            timeMachineTargetMs = null,
            isReadOnly = false,
            homeLoadState = HomeLoadState.ReadyEmpty,
        )

    private fun tag(id: Long, name: String): Tag =
        Tag(
            id = id,
            name = name,
            activeChildrenCount = 0,
            totalMs = 0L,
            lastStartedAtMs = null,
        )

    private fun tagTestTag(id: Long): String = "quick_start_tag_$id"

    private class FakeNowAccess(
        private val state: MutableStateFlow<NowUiState>,
    ) : NowCapsuleAccess {
        override fun uiStateFlow(): StateFlow<NowUiState> = state
        override fun addTag(name: String) = Unit
        override fun exportBackup(context: Context) = Unit
        override fun importDbFromUri(context: Context, uri: Uri) = Unit
        override fun setBackupRootFolder(context: Context, uri: Uri) = Unit
    }
}
