package com.example.multitimetracker.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.multitimetracker.R
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimedTagNotificationType
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionEditDialogRegressionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val normal = tag(1L, "Normal")
    private val focusTimed = tag(
        id = 2L,
        name = "Focus 25",
        timedDurationMinutes = 25,
        notificationType = TimedTagNotificationType.ALARM,
    )
    private val breakTimed = tag(
        id = 3L,
        name = "Break 15",
        timedDurationMinutes = 15,
        notificationType = TimedTagNotificationType.NORMAL,
    )

    @Before
    fun initializeHubContextRuntime() {
        HubContextRuntime.initialize(
            InstrumentationRegistry.getInstrumentation().targetContext,
            emptyList(),
        )
    }

    @Test
    fun normalEditorCreatesDraftExactlyOnceWithEditedTitleAndExistingTag() {
        val creates = mutableListOf<Triple<String, Long, Set<Long>>>()
        val saves = mutableListOf<Triple<Long, String, Set<Long>>>()
        var dismissCount = 0
        val draft = session(
            id = -1L,
            title = "",
            startMs = 1_000_000L,
            tagIds = setOf(normal.id),
        )

        setEditor(
            session = draft,
            isNewSession = true,
            tags = listOf(normal),
            onSaveMeta = { id, title, tagIds -> saves += Triple(id, title, tagIds) },
            onCreate = { title, startMs, tagIds, onCreated ->
                creates += Triple(title, startMs, tagIds)
                onCreated(draft.copy(id = 101L, title = title, startMs = startMs, tagIds = tagIds))
            },
            onDismiss = { dismissCount += 1 },
        )

        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Edited title")
        composeRule.onNodeWithContentDescription(targetString(R.string.salva)).performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(Triple("Edited title", draft.startMs, setOf(normal.id))),
                creates,
            )
            assertTrue(saves.isEmpty())
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun normalEditorExpandsTransitiveParentsBeforeCreatingSession() {
        val grandParent = tag(10L, "Grand parent")
        val parent = tag(11L, "Parent")
        val child = tag(12L, "Child")
        val creates = mutableListOf<Set<Long>>()
        val draft = session(id = -1L, startMs = 2_000_000L)

        setEditor(
            session = draft,
            isNewSession = true,
            tags = listOf(child, parent, grandParent),
            tagParentsByChild = mapOf(
                child.id to setOf(parent.id),
                parent.id to setOf(grandParent.id),
            ),
            onCreate = { title, startMs, tagIds, onCreated ->
                creates += tagIds
                onCreated(draft.copy(id = 102L, title = title, startMs = startMs, tagIds = tagIds))
            },
        )

        openTagPicker()
        composeRule.onNodeWithText(child.name).performTouchInput { click() }
        closeTopmostCancel()
        composeRule.onNodeWithContentDescription(targetString(R.string.salva)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(setOf(child.id, parent.id, grandParent.id)), creates)
        }
    }

    @Test
    fun normalEditorRejectsSecondTimedTagAndPreservesFirstTimedSelection() {
        val creates = mutableListOf<Set<Long>>()
        val draft = session(
            id = -1L,
            startMs = 3_000_000L,
            tagIds = setOf(focusTimed.id),
        )

        setEditor(
            session = draft,
            isNewSession = true,
            tags = listOf(focusTimed, breakTimed, normal),
            onCreate = { title, startMs, tagIds, onCreated ->
                creates += tagIds
                onCreated(draft.copy(id = 103L, title = title, startMs = startMs, tagIds = tagIds))
            },
        )

        openTagPicker()
        composeRule.onNodeWithText(breakTimed.name).performTouchInput { click() }
        closeTopmostCancel()
        composeRule.onNodeWithContentDescription(targetString(R.string.salva)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(setOf(focusTimed.id)), creates)
        }
    }

    @Test
    fun cancellingPositiveIdNewSessionDeletesOnlyThatPhantomSession() {
        val deleted = mutableListOf<Long>()
        var dismissCount = 0
        val phantom = session(id = 777L, startMs = 4_000_000L)

        setEditor(
            session = phantom,
            isNewSession = true,
            tags = listOf(normal),
            onDelete = { deleted += it },
            onDismiss = { dismissCount += 1 },
        )

        composeRule.onNodeWithContentDescription(targetString(R.string.annulla)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(phantom.id), deleted)
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun deletingExistingSessionRequiresConfirmationAndTargetsExactId() {
        val deleted = mutableListOf<Long>()
        val existing = session(id = 888L, title = "Existing", startMs = 5_000_000L)

        setEditor(
            session = existing,
            isNewSession = false,
            tags = listOf(normal),
            onDelete = { deleted += it },
        )

        val deleteLabel = targetString(R.string.elimina)
        composeRule.onNodeWithText(deleteLabel).performClick()
        composeRule.runOnIdle { assertTrue(deleted.isEmpty()) }
        composeRule.onNodeWithText(targetString(R.string.elimina_sessione)).assertIsDisplayed()

        val deleteNodes = composeRule.onAllNodesWithText(deleteLabel)
        val lastDeleteIndex = deleteNodes.fetchSemanticsNodes().lastIndex
        deleteNodes[lastDeleteIndex].performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(existing.id), deleted)
        }
    }

    private fun openTagPicker() {
        composeRule.onNodeWithText(targetString(R.string.cerca_tag))
            .performTouchInput { click() }
    }

    private fun closeTopmostCancel() {
        val cancelNodes = composeRule.onAllNodesWithContentDescription(targetString(R.string.annulla))
        val lastCancelIndex = cancelNodes.fetchSemanticsNodes().lastIndex
        cancelNodes[lastCancelIndex].performClick()
    }

    private fun setEditor(
        session: SessionUi,
        isNewSession: Boolean,
        tags: List<Tag>,
        tagParentsByChild: Map<Long, Set<Long>> = emptyMap(),
        readOnly: Boolean = false,
        onSaveMeta: (Long, String, Set<Long>) -> Unit = { _, _, _ -> },
        onCreate: (String, Long, Set<Long>, (SessionUi) -> Unit) -> Unit = { _, _, _, _ -> },
        onSaveTimes: (Long, Long, Long?) -> Unit = { _, _, _ -> },
        onDelete: (Long) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                SessionEditDialog(
                    session = session,
                    isNewSession = isNewSession,
                    tags = tags,
                    tagLastUsedMsByTagId = emptyMap(),
                    tagParentsByChild = tagParentsByChild,
                    showSeconds = false,
                    referenceNowMs = 10_000_000L,
                    readOnly = readOnly,
                    onAddTag = {},
                    onSaveMeta = onSaveMeta,
                    onCreateNewSession = onCreate,
                    onSaveTimes = onSaveTimes,
                    onDelete = onDelete,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    private fun targetString(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun session(
        id: Long,
        title: String = "",
        startMs: Long,
        endMs: Long? = null,
        tagIds: Set<Long> = emptySet(),
    ): SessionUi =
        SessionUi(
            id = id,
            title = title,
            startMs = startMs,
            endMs = endMs,
            tagIds = tagIds,
            deletedAtMs = null,
        )

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
}
