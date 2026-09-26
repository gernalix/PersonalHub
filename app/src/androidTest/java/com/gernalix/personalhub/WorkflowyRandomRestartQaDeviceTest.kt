package com.gernalix.personalhub

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.multitimetracker.TimeFenceTimerScheduler
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.ResourceHubAdapter
import com.gernalix.personalhub.core.hubcontext.WorkflowyHubBridge
import com.gernalix.personalhub.core.hubcontext.WorkflowyIntegrationSettings
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run prepare, kill the background .qa process, wait for its alarm, then run verify. */
@RunWith(AndroidJUnit4::class)
class WorkflowyRandomRestartQaDeviceTest {
    private val url = "https://workflowy.com/#/59d823cea257"
    private val title = "QA-TIMER-RANDOM-RESTART"
    private val prefsName = "qa_workflowy_random_restart"

    @Test fun prepare() = runBlocking {
        val context = qaContext()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        check(!prefs.contains("rule_id")) { "Previous random restart QA fixture needs cleanup" }
        assertTrue("Exact alarm permission is required for this QA", TimeFenceTimerScheduler.canScheduleExactAlarms(context))
        val previousGate = WorkflowyIntegrationSettings.isEnabled(context)
        val ruleId = UUID.randomUUID().mostSignificantBits ushr 1
        val identity = "qa-workflowy-random-$ruleId"
        val fireAt = System.currentTimeMillis() + 45_000L
        val anchor = HubEntityRef("timer", "alert", ruleId.toString())
        val adapters = HubContextRuntime.adapters().filter {
            it.moduleId != "tags" && !(it.moduleId == "timer" && it.entityKind == "alert")
        } + QaTimerAlertAdapter()
        HubContextRuntime.initialize(context, adapters)
        WorkflowyIntegrationSettings.setEnabled(context, true)
        try {
            val resource = WorkflowyHubBridge.attachUrl(context, anchor, url)
            prefs.edit()
                .putLong("rule_id", ruleId)
                .putString("resource_id", resource.ref.canonicalId)
                .putString("identity", identity)
                .putLong("fire_at", fireAt)
                .putBoolean("previous_gate", previousGate)
                .commit()
            TimeFenceTimerScheduler.scheduleRandomAlert(context, identity, fireAt, title, "ordinary alert text", ruleId)
            assertEquals(url, WorkflowyHubBridge.notificationUrl(context, anchor))
        } catch (failure: Throwable) {
            cleanup(context)
            throw failure
        } finally {
            initializeHubContextRuntime(context)
        }
    }

    @Test fun verifyAndCleanup() = runBlocking {
        val context = qaContext()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        check(prefs.contains("rule_id")) { "Random restart QA fixture was not prepared" }
        val ruleId = prefs.getLong("rule_id", -1L)
        val fireAt = prefs.getLong("fire_at", -1L)
        val identity = requireNotNull(prefs.getString("identity", null))
        val notificationId = identity.hashCode() xor fireAt.hashCode()
        val manager = context.getSystemService(NotificationManager::class.java)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            assertTrue("Scheduled random alert did not fire after process restart",
                manager.activeNotifications.any { it.id == notificationId &&
                    it.notification.extras.getString("android.title") == title })
            assertEquals(url, WorkflowyHubBridge.notificationUrl(
                context, HubEntityRef("timer", "alert", ruleId.toString())))
            device.openNotification()
            val row = device.wait(Until.findObject(By.text(title)), 10_000)
            assertNotNull("Random alert notification row is missing", row)
            row!!.visibleBounds.let { device.click(it.centerX(), it.centerY()) }
            assertTrue("Random alert tap did not open Workflowy",
                device.wait(Until.hasObject(By.pkg("com.workflowy.android")), 15_000))
        } finally {
            cleanup(context)
            device.pressHome()
        }
    }

    @Test fun cleanupFixture() = runBlocking { cleanup(qaContext()) }

    private suspend fun cleanup(context: Context) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (!prefs.contains("rule_id")) return
        val ruleId = prefs.getLong("rule_id", -1L)
        val fireAt = prefs.getLong("fire_at", -1L)
        val identity = prefs.getString("identity", null)
        val previousGate = prefs.getBoolean("previous_gate", false)
        val resourceId = prefs.getString("resource_id", null)
        try {
            WorkflowyIntegrationSettings.setEnabled(context, true)
            if (identity != null) {
                TimeFenceTimerScheduler.cancelRandomAlert(context, identity, fireAt)
                context.getSystemService(NotificationManager::class.java)
                    .cancel(identity.hashCode() xor fireAt.hashCode())
            }
            val anchor = HubEntityRef("timer", "alert", ruleId.toString())
            HubContextRuntime.contexts(anchor).forEach { HubContextRuntime.deleteContext(it.context.id) }
            if (resourceId != null) {
                (HubContextRuntime.adapter("hub", "resource") as ResourceHubAdapter).delete(resourceId)
            }
            prefs.edit().clear().commit()
        } finally {
            WorkflowyIntegrationSettings.setEnabled(context, previousGate)
        }
    }

    private fun qaContext(): Context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName.endsWith(".qa"))
    }

    private class QaTimerAlertAdapter : HubEntityAdapter {
        override val moduleId = "timer"
        override val entityKind = "alert"
        override val capabilities = setOf("alert")
        override suspend fun exists(canonicalId: String) = true
        override suspend fun lifecycle(canonicalId: String) = HubEntityLifecycle.ACTIVE
        override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.associateWith {
            HubEntitySummary(HubEntityRef(moduleId, entityKind, it), "QA random alert")
        }
        override suspend fun search(query: String, limit: Int) = emptyList<HubEntitySummary>()
        override suspend fun openTarget(canonicalId: String): HubOpenTarget? = null
    }
}
