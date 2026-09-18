package com.example.multitimetracker.capsules.now.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickStartIdleLayoutInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val alpha = tag(id = 1L, name = "Alpha")
    private val beta = tag(id = 2L, name = "Beta")
    private val gamma = tag(id = 3L, name = "Gamma")

    @Test
    fun noActiveSessionsShowsBodyQuickStartSearchAndExplicitNewSessionActionWithoutLegacyEmptyCard() {
        val context = targetContext()
        setNowScreen(capsuleFor(HomeLoadState.ReadyEmpty))

        val header = composeRule
            .onNodeWithText(context.getString(R.string.quick_start_header))
            .assertIsDisplayed()
        val search = composeRule
            .onNodeWithTag("quick_start_search")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(tagTestTag(alpha.id)).assertIsDisplayed()
        composeRule
            .onAllNodesWithText(context.getString(R.string.new_session))[0]
            .assertExists()
            .assertHasClickAction()
        composeRule
            .onNodeWithText(context.getString(R.string.now_running_empty_title))
            .assertDoesNotExist()

        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val headerBounds = header.fetchSemanticsNode().boundsInRoot
        val searchBounds = search.fetchSemanticsNode().boundsInRoot
        val rootMidpoint = rootBounds.top + rootBounds.height / 2f

        assertTrue(
            "Idle quick-start must begin in the upper half instead of leaving an empty-session block above it",
            headerBounds.top < rootMidpoint,
        )
        assertTrue(
            "Idle quick-start search must be visible near the start of the body",
            searchBounds.top < rootMidpoint && searchBounds.height > 0f,
        )
    }

    @Test
    fun idleSearchFiltersTagsAndShowsNoResultsState() {
        val context = targetContext()
        setNowScreen(capsuleFor(HomeLoadState.ReadyEmpty))

        val search = composeRule.onNodeWithTag("quick_start_search").assertIsDisplayed()
        search.performTextInput("Beta")

        composeRule.onNodeWithTag(tagTestTag(alpha.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(tagTestTag(beta.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(tagTestTag(gamma.id)).assertDoesNotExist()

        search.performTextClearance()
        search.performTextInput("not-a-real-tag")

        composeRule
            .onNodeWithText(context.getString(R.string.quick_start_no_results))
            .assertIsDisplayed()
    }

    @Test
    fun idleNewSessionActionRemainsAvailableAndOpensLegacyFullSessionEditor() {
        val context = targetContext()
        setNowScreen(capsuleFor(HomeLoadState.ReadyEmpty))

        composeRule
            .onAllNodesWithText(context.getString(R.string.new_session))[0]
            .assertExists()
            .performClick()

        composeRule
            .onAllNodesWithText(context.getString(R.string.new_session))[0]
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.session_dialog_tags_section))
            .assertIsDisplayed()
    }

    @Test
    fun loadingAndErrorStatesHideQuickStartAndPreserveTheirStatusUi() {
        val context = targetContext()
        val state = MutableStateFlow(nowState(HomeLoadState.Loading))
        val capsule = capsuleFor(state)
        setNowScreen(capsule)

        composeRule
            .onNodeWithText(context.getString(R.string.loading))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("quick_start_search").assertDoesNotExist()
        composeRule
            .onNodeWithText(context.getString(R.string.quick_start_header))
            .assertDoesNotExist()

        composeRule.runOnIdle {
            state.value = nowState(HomeLoadState.Error)
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(context.getString(R.string.integrity_gate_title))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("quick_start_search").assertDoesNotExist()
        composeRule
            .onNodeWithText(context.getString(R.string.quick_start_header))
            .assertDoesNotExist()
    }

    private fun capsuleFor(loadState: HomeLoadState): NowCapsuleViewModel =
        capsuleFor(MutableStateFlow(nowState(loadState)))

    private fun capsuleFor(state: MutableStateFlow<NowUiState>): NowCapsuleViewModel =
        NowCapsuleViewModel(
            access = FakeNowAccess(state),
            createNewSessionOverride = { title, startMs, tagIds, onCreated ->
                onCreated(
                    SessionUi(
                        id = 900L,
                        title = title,
                        startMs = startMs,
                        endMs = null,
                        tagIds = tagIds,
                        deletedAtMs = null,
                    )
                )
            },
            updateSessionOverride = { _, _, _ -> },
            updateSessionTimesOverride = { _, _, _ -> },
            deleteSessionOverride = {},
        )

    private fun setNowScreen(capsule: NowCapsuleViewModel) {
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
    }

    private fun nowState(loadState: HomeLoadState): NowUiState =
        NowUiState(
            tags = listOf(alpha, beta, gamma),
            chronologySessions = emptyList(),
            runningSessions = emptyList(),
            activeTagTotalsMsByTagId = emptyMap(),
            runningMinStartByTagId = emptyMap(),
            tagLastUsedMsByTagId = emptyMap(),
            tagParentsByChild = emptyMap(),
            nowMs = 1_000_000L,
            isReadOnly = false,
            homeLoadState = loadState,
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

    private fun targetContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeNowAccess(
        private val state: MutableStateFlow<NowUiState>,
    ) : NowCapsuleAccess {
        override fun uiStateFlow(): StateFlow<NowUiState> = state
        override fun addTag(name: String) = Unit
    }
}
