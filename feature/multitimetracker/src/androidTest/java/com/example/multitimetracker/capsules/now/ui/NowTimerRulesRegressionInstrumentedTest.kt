package com.example.multitimetracker.capsules.now.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.now.controller.NowCapsuleViewModel
import com.example.multitimetracker.capsules.now.state.NowUiState
import com.example.multitimetracker.capsules.system.NowCapsuleAccess
import com.example.multitimetracker.hub.TimerSessionHubAdapter
import com.example.multitimetracker.model.HomeLoadState
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowTimerRulesRegressionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val alpha = tag(1L, "Alpha")

    @Before
    fun initializeHubContextRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HubContextRuntime.initialize(context, listOf(TimerSessionHubAdapter(context)))
    }

    @Test
    fun tappingRunningSessionStopsExactSessionAtEffectiveNow() {
        val nowMs = 1_000_000L
        val running = runningSession(
            id = 101L,
            title = "Tap stop regression",
            startMs = 850_000L,
            tagIds = setOf(alpha.id),
        )
        val timeUpdates = mutableListOf<Triple<Long, Long, Long?>>()
        val capsule = capsuleFor(
            state = nowState(nowMs = nowMs, runningSessions = listOf(running)),
            onUpdateTimes = { id, startMs, endMs -> timeUpdates += Triple(id, startMs, endMs) },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(sessionTag(running.id)).performTouchInput { click() }

        composeRule.runOnIdle {
            assertEquals(listOf(Triple(running.id, running.startMs, nowMs)), timeUpdates)
        }
    }

    @Test
    fun longPressOpensNormalEditorWithoutStoppingOrDeletingSession() {
        val running = runningSession(
            id = 102L,
            title = "Long press editor regression",
            startMs = 850_000L,
            tagIds = setOf(alpha.id),
        )
        val timeUpdates = mutableListOf<Triple<Long, Long, Long?>>()
        val deleted = mutableListOf<Long>()
        val capsule = capsuleFor(
            state = nowState(nowMs = 1_000_000L, runningSessions = listOf(running)),
            onUpdateTimes = { id, startMs, endMs -> timeUpdates += Triple(id, startMs, endMs) },
            onDelete = { deleted += it },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(sessionTag(running.id)).performTouchInput { longClick() }

        composeRule.onNodeWithText(targetString(R.string.modifica_sessione)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(timeUpdates.isEmpty())
            assertTrue(deleted.isEmpty())
        }

        composeRule.onNodeWithContentDescription(targetString(R.string.annulla)).performClick()
        composeRule.runOnIdle {
            assertTrue(timeUpdates.isEmpty())
            assertTrue(deleted.isEmpty())
        }
    }

    @Test
    fun swipeRightOpensNormalEditorWithoutStoppingOrDeletingSession() {
        val running = runningSession(
            id = 103L,
            title = "Swipe edit regression",
            startMs = 840_000L,
            tagIds = setOf(alpha.id),
        )
        val timeUpdates = mutableListOf<Triple<Long, Long, Long?>>()
        val deleted = mutableListOf<Long>()
        val capsule = capsuleFor(
            state = nowState(nowMs = 1_000_000L, runningSessions = listOf(running)),
            onUpdateTimes = { id, startMs, endMs -> timeUpdates += Triple(id, startMs, endMs) },
            onDelete = { deleted += it },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(sessionTag(running.id)).performTouchInput { swipeRight() }

        composeRule.onNodeWithText(targetString(R.string.modifica_sessione)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(timeUpdates.isEmpty())
            assertTrue(deleted.isEmpty())
        }
    }

    @Test
    fun swipeLeftDeletesExactRunningSessionWithoutStoppingAnotherPath() {
        val running = runningSession(
            id = 104L,
            title = "Swipe delete regression",
            startMs = 830_000L,
            tagIds = setOf(alpha.id),
        )
        val timeUpdates = mutableListOf<Triple<Long, Long, Long?>>()
        val deleted = mutableListOf<Long>()
        val capsule = capsuleFor(
            state = nowState(nowMs = 1_000_000L, runningSessions = listOf(running)),
            onUpdateTimes = { id, startMs, endMs -> timeUpdates += Triple(id, startMs, endMs) },
            onDelete = { deleted += it },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(sessionTag(running.id)).performTouchInput { swipeLeft() }

        composeRule.runOnIdle {
            assertEquals(listOf(running.id), deleted)
            assertTrue(timeUpdates.isEmpty())
        }
    }

    @Test
    fun savingExistingSessionFromNormalEditorUpdatesMetadataOnly() {
        val running = runningSession(
            id = 105L,
            title = "Original editor title",
            startMs = 800_000L,
            tagIds = setOf(alpha.id),
        )
        val metadataUpdates = mutableListOf<Triple<Long, String, Set<Long>>>()
        val timeUpdates = mutableListOf<Triple<Long, Long, Long?>>()
        val deleted = mutableListOf<Long>()
        val capsule = capsuleFor(
            state = nowState(nowMs = 1_000_000L, runningSessions = listOf(running)),
            onUpdate = { id, title, tagIds -> metadataUpdates += Triple(id, title, tagIds) },
            onUpdateTimes = { id, startMs, endMs -> timeUpdates += Triple(id, startMs, endMs) },
            onDelete = { deleted += it },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(sessionTag(running.id)).performTouchInput { longClick() }
        composeRule.onNode(hasSetTextAction() and hasText("Original editor title"))
            .performTextReplacement("Updated editor title")
        composeRule.onNodeWithContentDescription(targetString(R.string.salva)).performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(Triple(running.id, "Updated editor title", setOf(alpha.id))),
                metadataUpdates,
            )
            assertTrue(timeUpdates.isEmpty())
            assertTrue(deleted.isEmpty())
        }
    }

    @Test
    fun deletingExistingSessionThroughNowEditorTargetsExactSessionAfterConfirmation() {
        val running = runningSession(
            id = 106L,
            title = "Delete editor regression",
            startMs = 810_000L,
            tagIds = setOf(alpha.id),
        )
        val deleted = mutableListOf<Long>()
        val capsule = capsuleFor(
            state = nowState(nowMs = 1_000_000L, runningSessions = listOf(running)),
            onDelete = { deleted += it },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(sessionTag(running.id)).performTouchInput { longClick() }
        val deleteLabel = targetString(R.string.elimina)
        composeRule.onNodeWithText(deleteLabel).performClick()
        composeRule.runOnIdle { assertTrue(deleted.isEmpty()) }
        composeRule.onNodeWithText(targetString(R.string.elimina_sessione)).assertIsDisplayed()

        val deleteNodes = composeRule.onAllNodesWithText(deleteLabel)
        deleteNodes[deleteNodes.fetchSemanticsNodes().lastIndex].performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(running.id), deleted)
        }
    }

    @Test
    fun readOnlyNowBlocksStopAndDoesNotOpenNormalEditor() {
        val running = runningSession(
            id = 107L,
            title = "Read only regression",
            startMs = 820_000L,
            tagIds = setOf(alpha.id),
        )
        val metadataUpdates = mutableListOf<Triple<Long, String, Set<Long>>>()
        val timeUpdates = mutableListOf<Triple<Long, Long, Long?>>()
        val deleted = mutableListOf<Long>()
        val capsule = capsuleFor(
            state = nowState(
                nowMs = 1_000_000L,
                runningSessions = listOf(running),
                readOnly = true,
            ),
            onUpdate = { id, title, tagIds -> metadataUpdates += Triple(id, title, tagIds) },
            onUpdateTimes = { id, startMs, endMs -> timeUpdates += Triple(id, startMs, endMs) },
            onDelete = { deleted += it },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(sessionTag(running.id)).performTouchInput { click() }
        composeRule.onNodeWithTag(sessionTag(running.id)).performTouchInput { longClick() }

        composeRule.onNodeWithText(targetString(R.string.modifica_sessione)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(metadataUpdates.isEmpty())
            assertTrue(timeUpdates.isEmpty())
            assertTrue(deleted.isEmpty())
        }
    }

    @Test
    fun timedDurationTapTogglesRemainingToElapsedWithoutStoppingSession() {
        val nowMs = 1_000_000L
        val timed = runningSession(
            id = 108L,
            title = "Timed display regression",
            startMs = 820_000L,
            expectedEndMs = 1_060_000L,
            tagIds = setOf(alpha.id),
        )
        val timeUpdates = mutableListOf<Triple<Long, Long, Long?>>()
        val capsule = capsuleFor(
            state = nowState(nowMs = nowMs, runningSessions = listOf(timed)),
            onUpdateTimes = { id, startMs, endMs -> timeUpdates += Triple(id, startMs, endMs) },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithText("-1m").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("3m").assertIsDisplayed()

        composeRule.runOnIdle {
            assertTrue(timeUpdates.isEmpty())
        }
    }

    @Test
    fun endedAndDeletedRowsAreNeverExposedAsRunningSessions() {
        val valid = runningSession(
            id = 109L,
            title = "Valid running row",
            startMs = 900_000L,
            tagIds = setOf(alpha.id),
        )
        val ended = runningSession(
            id = 110L,
            title = "Ended row must stay hidden",
            startMs = 800_000L,
            endMs = 850_000L,
            tagIds = setOf(alpha.id),
        )
        val deleted = runningSession(
            id = 111L,
            title = "Deleted row must stay hidden",
            startMs = 700_000L,
            deletedAtMs = 950_000L,
            tagIds = setOf(alpha.id),
        )
        val capsule = capsuleFor(
            state = nowState(
                nowMs = 1_000_000L,
                runningSessions = listOf(valid, ended, deleted),
            ),
        )
        setNowScreen(capsule)

        composeRule.onNodeWithText(valid.title).assertIsDisplayed()
        composeRule.onNodeWithText(ended.title).assertDoesNotExist()
        composeRule.onNodeWithText(deleted.title).assertDoesNotExist()
    }

    private fun capsuleFor(
        state: NowUiState,
        onUpdate: (Long, String, Set<Long>) -> Unit = { _, _, _ -> },
        onUpdateTimes: (Long, Long, Long?) -> Unit = { _, _, _ -> },
        onDelete: (Long) -> Unit = {},
    ): NowCapsuleViewModel =
        NowCapsuleViewModel(
            access = FakeNowAccess(MutableStateFlow(state)),
            updateSessionOverride = onUpdate,
            updateSessionTimesOverride = onUpdateTimes,
            deleteSessionOverride = onDelete,
            createNewSessionOverride = { _, _, _, _ -> },
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

    private fun nowState(
        nowMs: Long,
        runningSessions: List<SessionUi>,
        readOnly: Boolean = false,
    ): NowUiState {
        val runningMinStartByTagId = mutableMapOf<Long, Long>()
        runningSessions
            .filter { it.endMs == null && it.deletedAtMs == null }
            .forEach { session ->
                session.tagIds.forEach { tagId ->
                    val existing = runningMinStartByTagId[tagId]
                    runningMinStartByTagId[tagId] = existing?.let { minOf(it, session.startMs) } ?: session.startMs
                }
            }
        return NowUiState(
            tags = listOf(alpha),
            chronologySessions = runningSessions,
            runningSessions = runningSessions,
            activeTagTotalsMsByTagId = emptyMap(),
            runningMinStartByTagId = runningMinStartByTagId,
            tagLastUsedMsByTagId = runningMinStartByTagId,
            tagParentsByChild = emptyMap(),
            nowMs = nowMs,
            isReadOnly = readOnly,
            homeLoadState = HomeLoadState.ReadyWithData,
        )
    }

    private fun runningSession(
        id: Long,
        title: String,
        startMs: Long,
        endMs: Long? = null,
        expectedEndMs: Long? = null,
        deletedAtMs: Long? = null,
        tagIds: Set<Long>,
    ): SessionUi =
        SessionUi(
            id = id,
            title = title,
            startMs = startMs,
            endMs = endMs,
            expectedEndMs = expectedEndMs,
            tagIds = tagIds,
            deletedAtMs = deletedAtMs,
        )

    private fun tag(id: Long, name: String): Tag =
        Tag(
            id = id,
            name = name,
            activeChildrenCount = 0,
            totalMs = 0L,
            lastStartedAtMs = null,
        )

    private fun sessionTag(id: Long): String = "running_session_$id"

    private fun targetString(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private class FakeNowAccess(
        private val state: MutableStateFlow<NowUiState>,
    ) : NowCapsuleAccess {
        override fun uiStateFlow(): StateFlow<NowUiState> = state
        override fun addTag(name: String) = Unit
    }
}
