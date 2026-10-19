package com.gernalix.personalhub

import androidx.activity.ComponentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.Robolectric
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.history.HistoryQueryCapsule
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppUpdateTrackerTest {
    @Test fun startupRecreationPresentsThePendingUpdateOnTheReplacementActivity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<Long?>()
        val shown = mutableListOf<Pair<ComponentActivity, Long>>()
        var checks = 0
        val check = ResumedUpdateCheck(scope, { checks++; pending.await() }) { activity, version -> shown += activity to version }
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val replacement = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            check.onResume(first.get())
            check.onPause(first.get())
            first.pause().stop().destroy()
            check.onResume(replacement.get())
            pending.complete(70L)
            assertEquals(1, checks)
            assertEquals(listOf(replacement.get() to 70L), shown)
        } finally {
            check.onPause(replacement.get())
            replacement.pause().stop().destroy()
            scope.cancel()
        }
    }

    @Test fun backgroundCompletionDefersThePromptUntilForegroundWithoutAcknowledgingIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<Long?>()
        val shown = mutableListOf<Long>()
        var checks = 0
        val check = ResumedUpdateCheck(scope, { checks++; pending.await() }) { _, version -> shown += version }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            check.onResume(activity.get())
            check.onPause(activity.get())
            activity.pause()
            pending.complete(70L)
            assertTrue(shown.isEmpty())
            activity.resume()
            check.onResume(activity.get())
            assertEquals(2, checks)
            assertEquals(listOf(70L), shown)
        } finally {
            check.onPause(activity.get())
            activity.pause().stop().destroy()
            scope.cancel()
        }
    }

    @Test fun installedUpdateIsOneSharedAuditAndHistoryFactAcrossRetries() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
        try {
            val db = PersonalHubDatabase.get(context)
            assertTrue(recordAppUpdate(db, 70L, 69L))
            assertFalse(recordAppUpdate(db, 70L, 69L))
            val sql = db.openHelper.readableDatabase
            val audit = MutationEventStore.recent(sql).filter { it.eventType == "app.version.updated" }
            assertEquals(1, audit.size)
            assertEquals("settings", audit.single().module)
            assertEquals("69", org.json.JSONObject(audit.single().beforeJson!!).getString("version"))
            assertEquals("app aggiornata alla v70", semanticHistoryRows(audit).single().text.title)
            val history = HistoryQueryCapsule.page(sql, HistoryQueryCapsule.Filter(modules = setOf("settings")))
            assertTrue(history.groups.any { it.groupId == audit.single().transactionId })
            assertTrue(recordAppUpdate(db, 71L, 70L))
            assertEquals(2, MutationEventStore.recent(sql).count { it.eventType == "app.version.updated" })
        } finally {
            PersonalHubDatabase.resetForTests()
            context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
        }
    }
}
