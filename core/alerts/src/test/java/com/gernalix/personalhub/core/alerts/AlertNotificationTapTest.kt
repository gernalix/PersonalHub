package com.gernalix.personalhub.core.alerts

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.WorkflowyIntegrationSettings
import com.gernalix.personalhub.core.hubcontext.WorkflowyHubBridge
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.ResourceHubAdapter
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlertNotificationTapTest {
    @Test fun placesTimerAndRestartedRandomAlertTapsRespectGateAndPersistentLinks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapters = listOf(QaAlertAdapter("places"), QaAlertAdapter("timer"), ResourceHubAdapter(context))
        HubContextRuntime.initialize(context, adapters)
        WorkflowyIntegrationSettings.setEnabled(context, true)
        val url = "https://workflowy.com/#/59d823cea257"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        shadowOf(context.packageManager).addResolveInfoForIntent(view, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "browser.test"; name = "Viewer" }
        })
        val anchors = listOf(
            HubEntityRef("places", "alert", UUID.randomUUID().toString()),
            HubEntityRef("timer", "alert", UUID.randomUUID().toString()),
            HubEntityRef("timer", "alert", UUID.randomUUID().toString()),
        )
        anchors.forEach { anchor ->
            val owner = if (anchor.moduleId == "timer") "timer" else "alerts"
            PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL(
                "INSERT INTO hub_entities VALUES(?,?,?,?,?,'ACTIVE',1,1)",
                arrayOf(anchor.canonicalId, "$owner/alert", owner, "test-fixture.$owner.alert", anchor.canonicalId),
            )
        }
        val resources = anchors.map { WorkflowyHubBridge.attachUrl(context, it, url) }
        try {
            anchors.forEachIndexed { index, anchor ->
                if (index == 2) HubContextRuntime.initialize(context, adapters) // rebooted receiver runtime
                val linked = WorkflowyHubBridge.notificationUrl(context, anchor)
                assertEquals(url, linked)
                val onTap = AlertNotificationDispatcher.contentPendingIntent(
                    context, 10_100 + index, "ordinary alert text", linkedUrl = linked,
                )
                assertEquals(Intent.ACTION_VIEW, shadowOf(onTap).savedIntent.action)
                assertEquals(url, shadowOf(onTap).savedIntent.dataString)

                WorkflowyIntegrationSettings.setEnabled(context, false)
                assertNull(WorkflowyHubBridge.notificationUrl(context, anchor))
                val offTap = AlertNotificationDispatcher.contentPendingIntent(
                    context, 10_200 + index, "ordinary alert text", linkedUrl = url,
                    fallbackIntent = Intent(Intent.ACTION_MAIN).setPackage(context.packageName),
                )
                assertEquals(Intent.ACTION_MAIN, shadowOf(offTap).savedIntent.action)
                assertNull(shadowOf(offTap).savedIntent.data)
                WorkflowyIntegrationSettings.setEnabled(context, true)
                assertEquals(url, WorkflowyHubBridge.notificationUrl(context, anchor))
            }
        } finally {
            WorkflowyIntegrationSettings.setEnabled(context, true)
            anchors.zip(resources).forEach { (anchor, resource) ->
                WorkflowyHubBridge.delink(context, anchor, resource.ref)
                (HubContextRuntime.adapter("hub", "resource") as ResourceHubAdapter).delete(resource.ref.canonicalId)
            }
            WorkflowyIntegrationSettings.setEnabled(context, false)
        }
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

    @Test fun linkedWorkflowyNodeOverridesAlertMessage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkflowyIntegrationSettings.setEnabled(context, true)
        val url = "https://workflowy.com/#/59d823cea257"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        shadowOf(context.packageManager).addResolveInfoForIntent(view, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "browser.test"; name = "Viewer" }
        })

        val pending = AlertNotificationDispatcher.contentPendingIntent(
            context, 9917, "Remember this", linkedUrl = url,
        )
        val tap = shadowOf(pending).savedIntent
        assertEquals(Intent.ACTION_VIEW, tap.action)
        assertEquals(url, tap.dataString)
        WorkflowyIntegrationSettings.setEnabled(context, false)
        val disabled = AlertNotificationDispatcher.contentPendingIntent(
            context, 9918, url, linkedUrl = url,
        )
        assertEquals(null, shadowOf(disabled).savedIntent.data)
    }
}
