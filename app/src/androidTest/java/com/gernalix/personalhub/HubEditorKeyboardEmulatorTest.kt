package com.gernalix.personalhub

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gernalix.personalhub.core.ui.HubEditorDialog
import com.gernalix.personalhub.core.ui.HubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HubEditorKeyboardEmulatorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun portraitLastFieldAndSaveRemainVisibleWithTheKeyboard() = checkEditor(false)
    @Test fun landscapeLastFieldAndSaveRemainVisibleWithTheKeyboard() = checkEditor(true)

    private fun checkEditor(landscape: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        check(Build.MODEL.contains("sdk", true) || Build.HARDWARE in setOf("ranchu", "goldfish"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var saved: List<String>? = null
        try {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            compose.setContent {
                val fields = remember { List(8) { mutableStateOf("") } }
                HubTheme { HubEditorDialog(
                    onDismissRequest = {}, title = { Text("Editor") },
                    text = {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            fields.forEachIndexed { index, value ->
                                OutlinedTextField(value.value, { value.value = it }, singleLine = true,
                                    label = { Text("Field $index") }, modifier = Modifier.fillMaxWidth().testTag("field-$index"))
                            }
                        }
                    },
                    confirmButton = { Button(onClick = { saved = fields.map { it.value } }, modifier = Modifier.testTag("form-save")) { Text("Save") } },
                    dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                ) }
            }
            compose.onNodeWithTag("field-0").performTextReplacement("Retained first value")
            val last = compose.onNodeWithTag("field-7")
            last.performScrollTo().performClick().performTextInput("Last value")
            val imePackage = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).substringBefore('/')
            assertTrue(device.wait(Until.hasObject(By.pkg(imePackage)), 5_000))
            device.waitForIdle()
            last.assertIsDisplayed()
            compose.onNodeWithTag("form-save").assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertEquals("Retained first value", saved?.first())
                assertEquals("Last value", saved?.last())
            }
        } catch (failure: Throwable) {
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "shared-editor-keyboard-$landscape.png"))
            throw failure
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }
}
