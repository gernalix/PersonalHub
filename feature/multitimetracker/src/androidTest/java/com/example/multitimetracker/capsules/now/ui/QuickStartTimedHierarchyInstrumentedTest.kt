package com.example.multitimetracker.capsules.now.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.alerts.core.buildTimedSessionRestorePlan
import com.example.multitimetracker.capsules.now.controller.NowCapsuleViewModel
import com.example.multitimetracker.capsules.now.state.NowUiState
import com.example.multitimetracker.capsules.system.NowCapsuleAccess
import com.example.multitimetracker.model.HomeLoadState
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimedTagNotificationType
import com.example.multitimetracker.requireTimedSessionExpectation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickStartTimedHierarchyInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val focusTimed = tag(
        id = 10L,
        name = "Focus 25",
        timedDurationMinutes = 25,
        notificationType = TimedTagNotificationType.ALARM,
    )
    private val breakTimed = tag(
        id = 11L,
        name = "Break 15",
        timedDurationMinutes = 15,
        notificationType = TimedTagNotificationType.NORMAL,
    )
    private val silentTimed = tag(
        id = 12L,
        name = "Silent 10",
        timedDurationMinutes = 10,
        notificationType = TimedTagNotificationType.NONE,
    )
    private val normal = tag(id = 20L, name = "Normal")

    @Test
    fun timedQuickStartUsesConfiguredDurationForExpectedEnd() {
        val nowMs = 1_000_000L
        val tags = listOf(focusTimed, normal)
        val created = mutableListOf<SessionUi>()
        val capsule = capsuleFor(
            tags = tags,
            nowMs = nowMs,
            onCreate = { title, startMs, tagIds, onCreated ->
                val expectedEndMs = requireTimedSessionExpectation(
                    tagIds = tagIds,
                    tags = tags,
                    startMs = startMs,
                )
                val session = SessionUi(
                    id = 501L,
                    title = title,
                    startMs = startMs,
                    endMs = null,
                    expectedEndMs = expectedEndMs,
                    tagIds = tagIds,
                    deletedAtMs = null,
                )
                created += session
                onCreated(session)
            },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(tagTestTag(focusTimed.id)).performClick()

        composeRule.runOnIdle {
            val session = created.single()
            assertEquals(nowMs, session.startMs)
            assertEquals(setOf(focusTimed.id), session.tagIds)
            assertEquals(nowMs + 25L * 60_000L, session.expectedEndMs)
        }
    }

    @Test
    fun timedSessionExpiresExactlyAtExpectedEndAndFutureAlarmStops() {
        val startMs = 2_000_000L
        val expectedEndMs = requireTimedSessionExpectation(
            tagIds = setOf(focusTimed.id),
            tags = listOf(focusTimed),
            startMs = startMs,
        ) ?: error("Timed tag must produce an expected end")
        val session = SessionUi(
            id = 601L,
            title = "Timed",
            startMs = startMs,
            endMs = null,
            expectedEndMs = expectedEndMs,
            tagIds = setOf(focusTimed.id),
            deletedAtMs = null,
        )

        val beforeExpiry = buildTimedSessionRestorePlan(
            sessions = listOf(session),
            tags = listOf(focusTimed),
            nowMs = expectedEndMs - 1L,
        )
        assertTrue(beforeExpiry.expiredSessionIds.isEmpty())
        assertEquals(1, beforeExpiry.alarms.size)
        assertEquals(session.id, beforeExpiry.alarms.single().sessionId)
        assertEquals(expectedEndMs, beforeExpiry.alarms.single().fireAtMs)
        assertTrue(beforeExpiry.alarms.single().alarmStyle)

        val atExpiry = buildTimedSessionRestorePlan(
            sessions = listOf(session),
            tags = listOf(focusTimed),
            nowMs = expectedEndMs,
        )
        assertEquals(setOf(session.id), atExpiry.expiredSessionIds)
        assertTrue(atExpiry.alarms.isEmpty())
    }

    @Test
    fun notificationNoneKeepsTimedExpiryButDoesNotScheduleAlarm() {
        val startMs = 3_000_000L
        val expectedEndMs = requireTimedSessionExpectation(
            tagIds = setOf(silentTimed.id),
            tags = listOf(silentTimed),
            startMs = startMs,
        ) ?: error("Timed tag must produce an expected end")
        val session = SessionUi(
            id = 602L,
            title = "Silent timed",
            startMs = startMs,
            endMs = null,
            expectedEndMs = expectedEndMs,
            tagIds = setOf(silentTimed.id),
            deletedAtMs = null,
        )

        val plan = buildTimedSessionRestorePlan(
            sessions = listOf(session),
            tags = listOf(silentTimed),
            nowMs = expectedEndMs - 1L,
        )

        assertTrue(plan.expiredSessionIds.isEmpty())
        assertTrue(plan.alarms.isEmpty())
    }

    @Test
    fun timedAndNormalTagCanStartTogether() {
        val starts = mutableListOf<Set<Long>>()
        val capsule = capsuleFor(
            tags = listOf(focusTimed, normal),
            onCreate = { _, _, tagIds, _ -> starts += tagIds },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(tagTestTag(focusTimed.id)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(tagTestTag(normal.id)).performClick()
        composeRule.onNodeWithTag("quick_start_confirm").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(setOf(focusTimed.id, normal.id)), starts)
        }
    }

    @Test
    fun secondTimedTagIsRejectedByQuickStartMultiSelect() {
        val starts = mutableListOf<Set<Long>>()
        val capsule = capsuleFor(
            tags = listOf(focusTimed, breakTimed, normal),
            onCreate = { _, _, tagIds, _ -> starts += tagIds },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(tagTestTag(focusTimed.id)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(tagTestTag(breakTimed.id)).performClick()

        val error = targetContext().getString(R.string.timed_tag_single_per_session)
        composeRule.onAllNodesWithText(error)[0].assertIsDisplayed()
        composeRule.onNodeWithTag("quick_start_confirm").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(setOf(focusTimed.id)), starts)
        }
    }

    @Test
    fun quickStartExpandsFullTransitiveTagHierarchyBeforeCreate() {
        val grandParent = tag(id = 30L, name = "Grand parent")
        val parent = tag(id = 31L, name = "Parent")
        val child = tag(id = 32L, name = "Child")
        val starts = mutableListOf<Set<Long>>()
        val capsule = capsuleFor(
            tags = listOf(child, parent, grandParent),
            tagParentsByChild = mapOf(
                child.id to setOf(parent.id),
                parent.id to setOf(grandParent.id),
            ),
            onCreate = { _, _, tagIds, _ -> starts += tagIds },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(tagTestTag(child.id)).performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(setOf(child.id, parent.id, grandParent.id)),
                starts,
            )
        }
    }

    @Test
    fun multiSelectMergesSharedParentWithoutDuplicates() {
        val parent = tag(id = 40L, name = "Shared parent")
        val childA = tag(id = 41L, name = "Child A")
        val childB = tag(id = 42L, name = "Child B")
        val starts = mutableListOf<Set<Long>>()
        val capsule = capsuleFor(
            tags = listOf(childA, childB, parent),
            tagParentsByChild = mapOf(
                childA.id to setOf(parent.id),
                childB.id to setOf(parent.id),
            ),
            onCreate = { _, _, tagIds, _ -> starts += tagIds },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(tagTestTag(childA.id)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(tagTestTag(childB.id)).performClick()
        composeRule.onNodeWithTag("quick_start_confirm").performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(setOf(childA.id, childB.id, parent.id)),
                starts,
            )
        }
    }

    @Test
    fun hierarchyExpansionCannotBypassSingleTimedTagRule() {
        val timedParent = tag(
            id = 50L,
            name = "Timed parent",
            timedDurationMinutes = 20,
            notificationType = TimedTagNotificationType.NORMAL,
        )
        val child = tag(id = 51L, name = "Child with timed parent")
        val explicitTimed = tag(
            id = 52L,
            name = "Explicit timed",
            timedDurationMinutes = 10,
            notificationType = TimedTagNotificationType.NORMAL,
        )
        val starts = mutableListOf<Set<Long>>()
        val capsule = capsuleFor(
            tags = listOf(child, timedParent, explicitTimed),
            tagParentsByChild = mapOf(child.id to setOf(timedParent.id)),
            onCreate = { _, _, tagIds, _ -> starts += tagIds },
        )
        setNowScreen(capsule)

        composeRule.onNodeWithTag(tagTestTag(child.id)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(tagTestTag(explicitTimed.id)).performClick()
        composeRule.onNodeWithTag("quick_start_confirm").performClick()

        val error = targetContext().getString(R.string.timed_tag_single_per_session)
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            composeRule.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            assertTrue(starts.isEmpty())
        }
    }

    private fun capsuleFor(
        tags: List<Tag>,
        nowMs: Long = 1_000_000L,
        tagParentsByChild: Map<Long, Set<Long>> = emptyMap(),
        onCreate: (String, Long, Set<Long>, (SessionUi) -> Unit) -> Unit,
    ): NowCapsuleViewModel {
        val state = MutableStateFlow(
            NowUiState(
                tags = tags,
                chronologySessions = emptyList(),
                runningSessions = emptyList(),
                activeTagTotalsMsByTagId = emptyMap(),
                runningMinStartByTagId = emptyMap(),
                tagLastUsedMsByTagId = emptyMap(),
                tagParentsByChild = tagParentsByChild,
                nowMs = nowMs,
                isReadOnly = false,
                homeLoadState = HomeLoadState.ReadyEmpty,
            )
        )
        return NowCapsuleViewModel(
            access = FakeNowAccess(state),
            createNewSessionOverride = onCreate,
            updateSessionOverride = { _, _, _ -> },
            updateSessionTimesOverride = { _, _, _ -> },
            deleteSessionOverride = {},
        )
    }

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

    private fun tag(
        id: Long,
        name: String,
        timedDurationMinutes: Int? = null,
        notificationType: TimedTagNotificationType = TimedTagNotificationType.NONE,
    ): Tag =
        Tag(
            id = id,
            name = name,
            timedDurationMinutes = timedDurationMinutes,
            notificationType = notificationType,
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
