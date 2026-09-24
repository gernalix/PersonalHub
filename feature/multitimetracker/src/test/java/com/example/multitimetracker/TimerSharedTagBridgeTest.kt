package com.example.multitimetracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimedTagNotificationType
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataTracking
import com.gernalix.personalhub.core.hubcontext.SharedTagEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerSharedTagBridgeTest {
    @Test fun repeatedNowProjectionProducesNoGitWritesWhenNothingChanged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "timer-shared-tags-noop.db"
        val database = PersonalHubDatabase.openTemporary(context, name)
        try {
            GitDataTracking.install(database.openHelper.writableDatabase, enqueueAll = false)
            val bridge = TimerSharedTagBridge(database)
            val tag = Tag(
                id = 7, name = "Focus", timedDurationMinutes = 25,
                notificationType = TimedTagNotificationType.NORMAL,
                activeChildrenCount = 0, totalMs = 0, lastStartedAtMs = null,
            )
            val sessions = listOf(SessionUi(11, "Work", 1, 2, tagIds = setOf(7)))
            bridge.syncNow(listOf(tag), sessions)
            val firstUpdatedAt = requireNotNull(database.hubTagDao().tag("timer.now:7")).updatedAt
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("DELETE FROM hub_git_events")
            sqlite.execSQL("DELETE FROM hub_git_pending")

            bridge.syncNow(listOf(tag), sessions)

            assertEquals(firstUpdatedAt, requireNotNull(database.hubTagDao().tag("timer.now:7")).updatedAt)
            sqlite.query("SELECT count(*) FROM hub_git_events").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sqlite.query("SELECT count(*) FROM hub_git_pending").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun nowProjectionKeepsDefinitionsMetadataAndSessionAssignmentsInCommonAuthority() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "timer-shared-tags.db"
        val database = PersonalHubDatabase.openTemporary(context, name)
        try {
            val bridge = TimerSharedTagBridge(database)
            val tag = Tag(
                id = 7, name = "Focus", timedDurationMinutes = 25,
                notificationType = TimedTagNotificationType.NORMAL,
                activeChildrenCount = 0, totalMs = 0, lastStartedAtMs = null,
            )
            bridge.syncNow(listOf(tag), listOf(SessionUi(11, "Work", 1, 2, tagIds = setOf(7))))
            val engine = SharedTagEngine(database)
            assertEquals(listOf("timer.now:7"), engine.tags(HubEntityRef("timer", "session", "11")).map { it.id })
            assertTrue(database.hubTagDao().tag("timer.now:7")!!.metadataJson!!.contains("timedDurationMinutes"))

            bridge.syncNow(listOf(tag.copy(name = "Deep focus", isArchived = true)), emptyList())
            val updated = database.hubTagDao().tag("timer.now:7")!!
            assertEquals("Deep focus", updated.name)
            assertTrue(updated.archived)
            assertTrue(database.hubTagDao().allTags().none { it.namespace != HubTagNamespaces.TIMER_NOW })
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
