package com.gernalix.personalhub

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.ui.ModuleHistoryHost
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared scenario; each concrete runner retains its own target safety boundary. */
abstract class CountdownQaScenario {
    protected abstract fun verifyTarget(context: Context)
    @get:Rule val compose = createComposeRule()

    @Test fun createEditTagsAndSharedHistoryUseTheCountdownScope() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        verifyTarget(context)
        val db = PersonalHubDatabase.get(context)
        initializeHubContextRuntime(context)
        val tags = HubContextRuntime.tags()
        val title = "QA Countdown ${System.currentTimeMillis()}"
        val countdownTag = runBlocking { tags.getOrCreate(HubTagNamespaces.COUNTDOWN, title) }
        val sinceTag = runBlocking { tags.getOrCreate(HubTagNamespaces.SINCE_WHEN, title) }
        val timerTag = runBlocking { tags.getOrCreate(HubTagNamespaces.TIMER_NOW, title) }
        var saved: SinceWhenCounterEntity? = null
        try {
            compose.setContent { PersonalHubTheme {
                ModuleHistoryHost("countdown") { SinceWhenScreen(onBack = {}, mode = CounterModes.COUNTDOWN) }
            } }
            compose.onNodeWithTag("sincewhen-create").performClick()
            compose.onNodeWithTag("sincewhen-title").performTextReplacement(title)
            compose.onNodeWithTag("sincewhen-tag-${countdownTag.id}").performScrollTo().performClick()
            compose.onNodeWithTag("sincewhen-tag-${sinceTag.id}").assertDoesNotExist()
            compose.onNodeWithTag("sincewhen-tag-${timerTag.id}").assertDoesNotExist()
            compose.onNodeWithTag("sincewhen-save").performClick()
            compose.waitUntil(10_000) { runBlocking { db.sinceWhenCounterDao().all().any { it.title == title } } }
            saved = runBlocking { db.sinceWhenCounterDao().all().single { it.title == title } }
            assertEquals(CounterModes.COUNTDOWN, saved.mode)
            assertTrue(saved.initialTimestamp > System.currentTimeMillis())
            val ref = HubEntityRef("since_when", "counter", saved.canonicalId)
            assertEquals(setOf(countdownTag.id), runBlocking { tags.tags(ref).map { it.id }.toSet() })
            compose.onNode(hasText("−", substring = true)).assertIsDisplayed()
            compose.onNodeWithTag("sincewhen-edit-${saved.id}", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("sincewhen-description").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sincewhen-description").performTextReplacement("Edited in Countdown")
            compose.onNodeWithTag("sincewhen-save").performClick()
            compose.waitUntil(10_000) { runBlocking { db.sinceWhenCounterDao().get(saved.id)?.description == "Edited in Countdown" } }
            val edited = runBlocking { requireNotNull(db.sinceWhenCounterDao().get(saved.id)) }
            assertEquals(saved.initialTimestamp, edited.initialTimestamp)
            assertEquals(saved.canonicalId, edited.canonicalId)
            compose.onNodeWithTag("module-history-tab").performClick()
            compose.onNodeWithTag("history-module-filter").assertDoesNotExist()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title, substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("module-content-tab").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
        } finally {
            runBlocking {
                (saved ?: db.sinceWhenCounterDao().all().firstOrNull { it.title == title })?.let {
                    tags.remove(HubEntityRef("since_when", "counter", it.canonicalId), listOf(countdownTag.id))
                    db.sinceWhenCounterDao().get(it.id)?.let { row -> db.sinceWhenCounterDao().delete(row) }
                }
                listOf(countdownTag, sinceTag, timerTag).forEach { tags.deleteUnused(it.id) }
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class CountdownQaDeviceTest : CountdownQaScenario() {
    override fun verifyTarget(context: Context) {
        check(context.packageName == "com.gernalix.personalhub.qa" && Build.MODEL.contains("sdk", true))
    }
}

/** Explicit physical QA runner: isolated package only, never the user's main app. */
@RunWith(AndroidJUnit4::class)
class CountdownPixelQaDeviceTest : CountdownQaScenario() {
    override fun verifyTarget(context: Context) {
        check(context.packageName == "com.gernalix.personalhub.qa")
        check(Build.MANUFACTURER.equals("Google", true) && Build.MODEL == "Pixel 8a")
        check(androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("physicalQaAuthorized") == "true")
    }
}
