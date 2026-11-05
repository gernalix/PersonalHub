package com.gernalix.personalhub

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.capsules.shortcuts.HubModule
import com.gernalix.personalhub.capsules.shortcuts.LauncherShortcutsCapsule
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity, real session writer and real database; never run on a personal device. */
@RunWith(AndroidJUnit4::class)
class UiRedesignEmulatorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun verifyTarget() {
        check(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        check(Build.MODEL.contains("sdk", true) || Build.HARDWARE in setOf("goldfish", "ranchu"))
    }

    @Test fun timerReopensPreservedCanonicalHistoryWithoutLegacyBootstrap() {
        verifyTarget()
        val result = com.example.multitimetracker.persistence.DataIntegrityGate.runCriticalChecks(context)
        assertTrue(result.technicalReport, result.ok)
        val vm = com.example.multitimetracker.MainViewModel()
        val store = androidx.lifecycle.ViewModelStore()
        compose.runOnUiThread { store.put("timer-diagnostic", vm) }
        try {
            vm.initialize(context)
            assertTrue(vm.integrityBlock.value?.technicalReport, vm.integrityBlock.value?.ok != false)
        } finally { compose.runOnUiThread { store.clear() } }
    }

    @Test fun homeSearchKeepsVisibleLabelsAndFindsTimerWithoutScrolling() {
        verifyTarget()
        compose.onNodeWithTag("home-layout-switch").performClick()
        compose.onNode(hasText(context.getString(R.string.module_timer)) and hasClickAction() and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithTag("home-search").performTextReplacement("Timer")
        compose.onNode(hasText(context.getString(R.string.module_timer)) and hasClickAction() and !hasSetTextAction()).assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("module-switcher").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("module-home").assertIsDisplayed()
        compose.onNodeWithTag("module-switcher").performClick()
        compose.onNodeWithText(context.getString(R.string.module_soldi)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("module-switcher").assertIsDisplayed()
    }

    @Test fun landscapeTimerEditorKeepsTheFocusedFieldAndSaveVisibleAboveTheKeyboard() {
        verifyTarget()
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        try {
            device.setOrientationLeft()
            compose.runOnUiThread { compose.activity.startActivity(LauncherShortcutsCapsule.moduleIntent(context, HubModule.TIMER)) }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("new-session-action").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("new-session-action").assertIsDisplayed().performClick()
            val field = compose.onNode(hasSetTextAction() and hasText(context.getString(com.example.multitimetracker.R.string.titolo_opzionale)))
            field.performClick().performTextInput("Landscape draft")
            val imePackage = android.provider.Settings.Secure.getString(context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).substringBefore('/')
            assertTrue("The software keyboard must actually be visible",
                device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(imePackage)), 5_000))
            device.waitForIdle()
            compose.waitUntil(5_000) { field.isDisplayed() }
            field.assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(com.example.multitimetracker.R.string.salva)).assertIsDisplayed()
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "landscape-timer-keyboard.png"))
            compose.onNodeWithContentDescription(context.getString(com.example.multitimetracker.R.string.annulla)).performClick()
        } catch (failure: Throwable) {
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "landscape-timer-failure.png"))
            device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "landscape-timer-failure.xml"))
            throw failure
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test fun newlyCreatedTimerCanBeEditedTwiceAndDeletedWithoutActivityRestart() {
        verifyTarget()
        val token = "UIQA ${System.currentTimeMillis()}"
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        fun sessionId(): Long? = db.query("SELECT id FROM sessions WHERE title LIKE ? ORDER BY id DESC LIMIT 1", arrayOf("$token%"))
            .use { if (it.moveToFirst()) it.getLong(0) else null }
        fun title(id: Long): String? = db.query("SELECT title FROM sessions WHERE id=? AND deleted_at_ms IS NULL", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        var id: Long? = null
        try {
            compose.runOnUiThread { compose.activity.startActivity(LauncherShortcutsCapsule.moduleIntent(context, HubModule.TIMER)) }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("new-session-action").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("new-session-action").performClick()
            compose.onNode(hasSetTextAction() and hasText(context.getString(com.example.multitimetracker.R.string.titolo_opzionale))).performClick().performTextReplacement(token)
            compose.onNodeWithContentDescription(context.getString(com.example.multitimetracker.R.string.salva)).performClick()
            compose.waitUntil(15_000) { sessionId() != null }
            id = requireNotNull(sessionId())
            val stableId = id
            for (revision in 1..2) {
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("edit_session_$stableId").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("edit_session_$stableId").performScrollTo().performClick()
                compose.onNode(hasSetTextAction() and hasText(if (revision == 1) token else "$token 1"))
                    .performClick().performTextReplacement("$token $revision")
                compose.onNodeWithContentDescription(context.getString(com.example.multitimetracker.R.string.salva)).assertIsDisplayed().performClick()
                compose.waitUntil(15_000) { title(stableId) == "$token $revision" }
                compose.waitUntil(15_000) {
                    runCatching { compose.onNodeWithText("$token $revision").isDisplayed() }.getOrDefault(false)
                }
            }
            compose.onNodeWithTag("edit_session_$stableId").performClick()
            compose.onNodeWithText(context.getString(com.example.multitimetracker.R.string.elimina)).performScrollTo().performClick()
            compose.onAllNodesWithText(context.getString(com.example.multitimetracker.R.string.elimina)).onLast().performClick()
            compose.waitUntil(15_000) { title(stableId) == null }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("running_session_$stableId").fetchSemanticsNodes().isEmpty() }
            val restarted = com.example.multitimetracker.MainViewModel()
            val store = androidx.lifecycle.ViewModelStore()
            compose.runOnUiThread { store.put("timer-reopen", restarted) }
            try {
                restarted.initialize(context)
                assertTrue(restarted.integrityBlock.value?.technicalReport, restarted.integrityBlock.value?.ok != false)
                assertTrue(restarted.nowCapsule.uiState.value.runningSessions.none { it.id == stableId })
            } finally { compose.runOnUiThread { store.clear() } }
        } finally {
            // Retain all pre-existing QA data; only remove this scenario's synthetic row.
            (id ?: sessionId())?.let { db.execSQL("UPDATE sessions SET deleted_at_ms=? WHERE id=?", arrayOf(System.currentTimeMillis(), it)) }
        }
    }
}
