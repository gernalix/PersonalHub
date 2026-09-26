package com.gernalix.personalhub

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.multitimetracker.TimeFenceTimerReceiver
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import com.gernalix.personalhub.core.alerts.AlertNotificationDispatcher
import com.gernalix.personalhub.core.database.DatabaseProfiles
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

@RunWith(AndroidJUnit4::class)
class WorkflowyNotificationTapQaDeviceTest {
    @Test fun placesTimerAndRandomAfterRuntimeRestartTapToNodeOnlyWhenOn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        val originalGate = WorkflowyIntegrationSettings.isEnabled(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val adapters = HubContextRuntime.adapters().filter {
            it.moduleId != "tags" &&
                !(it.entityKind == "alert" && it.moduleId in setOf("places", "timer"))
        } + QaAlertAdapter("places") + QaAlertAdapter("timer")
        val anchorPlaces = HubEntityRef("places", "alert", UUID.randomUUID().toString())
        val anchorTimer = HubEntityRef("timer", "alert", (UUID.randomUUID().mostSignificantBits ushr 1).toString())
        val url = "https://workflowy.com/#/59d823cea257"
        val created = mutableListOf<Pair<HubEntityRef, HubEntityRef>>()
        val notificationIds = mutableListOf<Int>()
        try {
            HubContextRuntime.initialize(context, adapters)
            WorkflowyIntegrationSettings.setEnabled(context, true)
            for (anchor in listOf(anchorPlaces, anchorTimer)) {
                created += anchor to WorkflowyHubBridge.attachUrl(context, anchor, url).ref
            }
            val cases = listOf(
                Triple("places", anchorPlaces, 30101),
                Triple("timer", anchorTimer, 30102),
                Triple("random-after-restart", anchorTimer, 30103),
            )
            for ((kind, anchor, baseId) in cases) {
                if (kind == "random-after-restart") {
                    // Rebuild the same routing state that Application.onCreate builds after process restart.
                    HubContextRuntime.initialize(context, adapters)
                }
                for (enabled in listOf(true, false)) {
                    WorkflowyIntegrationSettings.setEnabled(context, enabled)
                    val id = baseId + if (enabled) 0 else 100
                    val title = "QA-$kind-${if (enabled) "ON" else "OFF"}"
                    notificationIds += id
                    if (kind == "random-after-restart") {
                        context.sendBroadcast(Intent(context, TimeFenceTimerReceiver::class.java).apply {
                            action = TimeFenceTimerReceiver.ACTION_RANDOM_ALERT
                            putExtra(TimeFenceTimerReceiver.EXTRA_PROFILE_ID, DatabaseProfiles.activeProfileId(context))
                            putExtra(TimeFenceTimerReceiver.EXTRA_ALERT_RULE_ID, anchor.canonicalId.toLong())
                            putExtra(TimeFenceTimerReceiver.EXTRA_NOTIFICATION_ID, id)
                            putExtra(TimeFenceTimerReceiver.EXTRA_RANDOM_ALERT_TITLE, title)
                            putExtra(TimeFenceTimerReceiver.EXTRA_RANDOM_ALERT_TEXT, "ordinary alert text")
                        })
                    } else {
                        val linked = WorkflowyHubBridge.notificationUrl(context, anchor)
                        assertEquals(if (enabled) url else null, linked)
                        assertTrue(AlertNotificationDispatcher.postNotification(
                            context, id, title, "ordinary alert text", linked,
                        ))
                    }
                    assertTrue("Missing $title notification", waitForNotification(notificationManager, id))
                    device.openNotification()
                    val row = device.wait(Until.findObject(By.text(title)), 10_000)
                    assertNotNull("Missing $title notification row", row)
                    row!!.visibleBounds.let { bounds ->
                        device.click(bounds.centerX(), bounds.centerY())
                    }
                    val targetPackage = if (enabled) "com.workflowy.android" else context.packageName
                    assertTrue("$title tap did not open $targetPackage", device.wait(
                        Until.hasObject(By.pkg(targetPackage)), 15_000,
                    ))
                    device.pressHome()
                }
            }
        } finally {
            notificationIds.forEach(notificationManager::cancel)
            WorkflowyIntegrationSettings.setEnabled(context, true)
            created.forEach { (anchor, resource) ->
                WorkflowyHubBridge.delink(context, anchor, resource)
                (HubContextRuntime.adapter("hub", "resource") as ResourceHubAdapter).delete(resource.canonicalId)
            }
            initializeHubContextRuntime(context)
            WorkflowyIntegrationSettings.setEnabled(context, originalGate)
            device.pressHome()
        }
    }

    private fun waitForNotification(manager: NotificationManager, id: Int): Boolean {
        repeat(50) {
            if (manager.activeNotifications.any { it.id == id }) return true
            Thread.sleep(100)
        }
        return false
    }

    private class QaAlertAdapter(override val moduleId: String) : HubEntityAdapter {
        override val entityKind = "alert"
        override val capabilities = setOf("alert")
        override suspend fun exists(canonicalId: String) = true
        override suspend fun lifecycle(canonicalId: String) = HubEntityLifecycle.ACTIVE
        override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.associateWith {
            HubEntitySummary(HubEntityRef(moduleId, entityKind, it), "QA alert")
        }
        override suspend fun search(query: String, limit: Int) = emptyList<HubEntitySummary>()
        override suspend fun openTarget(canonicalId: String): HubOpenTarget? = null
    }
}
