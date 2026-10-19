package com.gernalix.personalhub

import android.content.Intent
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.gernalix.personalhub.capsules.shortcuts.HubModule
import com.gernalix.personalhub.capsules.shortcuts.LauncherShortcutsCapsule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Actual module/ViewModel/Room collectors, using only search UI in the QA package. */
class ModuleSearchCursorQaDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun peopleKeepsTheCursorAcrossLiveContactFiltering() = checkModule(HubModule.PEOPLE)
    @Test fun substancesKeepsTheCursorAcrossLiveStatusRefreshes() = checkModule(HubModule.SUBSTANCES)
    @Test fun wordPulseKeepsTheCursorAcrossLiveSearchResults() = checkModule(HubModule.WORDPULSE)

    private fun checkModule(module: HubModule) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        assertTrue(Build.MODEL.startsWith("sdk") && Build.HARDWARE in setOf("goldfish", "ranchu"))
        // This test measures editing, not cold creation of the full canonical DB.
        // Ensure its real isolated fixture exists before the module startup deadline.
        com.gernalix.personalhub.core.database.PersonalHubDatabase.get(context).openHelper.writableDatabase
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(LauncherShortcutsCapsule.moduleIntent(context, module).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val denyPermission = By.res(java.util.regex.Pattern.compile(".*:id/permission_deny_button"))
            .pkg(java.util.regex.Pattern.compile("com\\.(google\\.android|android)\\.permissioncontroller"))
        try {
            // Fresh CI can request several permissions after cold database setup.
            // Drain only Deny on the isolated emulator; never grant/import contacts.
            compose.waitUntil(10_000) {
                device.findObject(denyPermission)?.click()
                device.currentPackageName == context.packageName && runCatching {
                    compose.onAllNodesWithTag("module-content-tab").fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            val diagnostic = java.io.File(context.getExternalFilesDir(null), "module-search-startup-${module.name}")
            runCatching { device.dumpWindowHierarchy(java.io.File(diagnostic.path + ".xml")) }
            runCatching { device.takeScreenshot(java.io.File(diagnostic.path + ".png")) }
            throw AssertionError("Module ${module.name} startup failed; foreground=${device.currentPackageName}; evidence=${diagnostic.name}", failure)
        }
        assertEquals(context.packageName, device.currentPackageName)
        compose.onNodeWithTag("module-content-tab").performClick()
        if (module == HubModule.WORDPULSE) compose.onNode(hasText("Explore") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val field = if (module == HubModule.WORDPULSE) {
            compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithTag("word-search-input").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            compose.onNodeWithTag("word-search-input").performScrollTo()
        } else compose.onAllNodes(hasSetTextAction()).onFirst()
        val original = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        try {
            field.assertIsDisplayed().performClick().performTextReplacement("alpha beta")
            field.performTextInputSelection(TextRange(5))
            field.performTextInput("XY")
            assertEquals("alphaXY beta", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(7)))
            field.performTextInputSelection(TextRange(2))
            // Real collectors and periodic module state may update while the user
            // has not touched the field. They must not move the caret.
            repeat(4) {
                Thread.sleep(350)
                compose.waitForIdle()
                assertEquals("alphaXY beta", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
            }
        } finally {
            field.performTextReplacement(original)
            device.pressHome()
        }
    }
}
