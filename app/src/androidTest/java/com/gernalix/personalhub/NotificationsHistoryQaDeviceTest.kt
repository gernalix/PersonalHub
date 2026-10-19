package com.gernalix.personalhub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.notifications.api.NotificationsHistory
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared scenario; each concrete runner retains its own target safety boundary. */
abstract class NotificationsHistoryQaScenario {
    protected abstract fun verifyTarget(context: Context)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun observedAndroidNotificationAppearsInSharedHistoryWithoutACanonicalCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        verifyTarget(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val manager = context.getSystemService(NotificationManager::class.java)
        val component = ComponentName(context.packageName, "com.gernalix.personalhub.notifications.capsules.archive.CollectorService")
        val previouslyAllowed = manager.isNotificationListenerAccessGranted(component)
        val channel = "ph-history-qa-${System.currentTimeMillis()}"
        val title = "PH shared History QA ${System.currentTimeMillis()}"
        val show = mutableStateOf(true)
        val beforeSecure = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
        val sql = PersonalHubDatabase.get(context).openHelper.readableDatabase
        fun canonicalCount() = sql.query("SELECT count(*) FROM mutation_events WHERE module='notifications'").use { it.moveToFirst(); it.getLong(0) }
        val beforeCount = canonicalCount()
        try {
            // The exclusive-session harness grants the isolated QA package permission.
            // Revoking our own permission in finally would kill instrumentation.
            assertTrue("QA notification posting permission is required", manager.areNotificationsEnabled())
            if (!previouslyAllowed) device.executeShellCommand("cmd notification allow_listener ${component.flattenToString()}")
            compose.waitUntil(10_000) { context.getSharedPreferences("notification_status", 0).getBoolean("connected", false) }
            manager.createNotificationChannel(NotificationChannel(channel, "PH shared History QA", NotificationManager.IMPORTANCE_LOW))
            manager.notify(193719, NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText("Observed by the real Android notification listener").build())
            compose.waitUntil(10_000) { NotificationsHistory.query(context, title, null, null, null, null, 10).isNotEmpty() }
            compose.setContent { PersonalHubTheme {
                if (show.value) HubHistorySearchScreen(onBack = {}, scopeModuleId = "notifications", initialQuery = title)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title, substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(title, substring = true).onFirst().assertIsDisplayed()
            compose.onNodeWithTag("history-module-filter").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                show.value = false
            }
            compose.waitForIdle()
            assertEquals(beforeSecure, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertEquals(beforeCount, canonicalCount())
        } finally {
            manager.cancel(193719)
            manager.deleteNotificationChannel(channel)
            if (!previouslyAllowed) device.executeShellCommand("cmd notification disallow_listener ${component.flattenToString()}")
        }
    }
}

@RunWith(AndroidJUnit4::class)
class NotificationsHistoryQaDeviceTest : NotificationsHistoryQaScenario() {
    override fun verifyTarget(context: Context) {
        check(context.packageName == "com.gernalix.personalhub.qa" && Build.MODEL.contains("sdk", true))
    }
}

/** Explicit physical QA runner: isolated package only, never the user's main app. */
@RunWith(AndroidJUnit4::class)
class NotificationsHistoryPixelQaDeviceTest : NotificationsHistoryQaScenario() {
    override fun verifyTarget(context: Context) {
        check(context.packageName == "com.gernalix.personalhub.qa")
        check(Build.MANUFACTURER.equals("Google", true) && Build.MODEL == "Pixel 8a")
        check(androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("physicalQaAuthorized") == "true")
    }
}
