package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.ResourceHubAdapter
import com.gernalix.personalhub.core.hubcontext.WorkflowyHubBridge
import com.gernalix.personalhub.core.hubcontext.WorkflowyIntegrationSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkflowyGateQaDeviceTest {
    @Test fun offOnKeepsExistingKeyConfigurationAndLink() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        val initial = WorkflowyIntegrationSettings.configuration(context)
        check(initial.hasApiKey) { "Pixel .qa Workflowy API key must already be configured" }
        val keyFile = File(context.noBackupFilesDir, "workflowy-api.enc")
        val secretBefore = keyFile.readBytes()
        val adapters = HubContextRuntime.adapters().filter {
            it.moduleId != "tags" && !(it.moduleId == "timer" && it.entityKind == "alert")
        } + QaTimerAlertAdapter()
        val anchor = HubEntityRef("timer", "alert", UUID.randomUUID().toString())
        val url = "https://workflowy.com/#/59d823cea257"
        var resourceRef: HubEntityRef? = null
        try {
            HubContextRuntime.initialize(context, adapters)
            WorkflowyIntegrationSettings.setEnabled(context, true)
            resourceRef = WorkflowyHubBridge.attachUrl(context, anchor, url).ref
            assertEquals(url, WorkflowyHubBridge.notificationUrl(context, anchor))
            WorkflowyIntegrationSettings.setEnabled(context, false)
            assertNull(WorkflowyHubBridge.notificationUrl(context, anchor))
            assertFalse(WorkflowyHubBridge.open(context, url))
            assertTrue(runCatching { WorkflowyHubBridge.attachUrl(context, anchor, url) }.isFailure)
            assertEquals(initial.hasApiKey, WorkflowyIntegrationSettings.configuration(context).hasApiKey)
            assertEquals(initial.target, WorkflowyIntegrationSettings.target(context))
            assertArrayEquals(secretBefore, keyFile.readBytes())
            WorkflowyIntegrationSettings.setEnabled(context, true)
            assertEquals(url, WorkflowyHubBridge.notificationUrl(context, anchor))
            assertEquals(1, HubContextRuntime.contexts(anchor).size)
            assertArrayEquals(secretBefore, keyFile.readBytes())
        } finally {
            WorkflowyIntegrationSettings.setEnabled(context, true)
            resourceRef?.let {
                WorkflowyHubBridge.delink(context, anchor, it)
                (HubContextRuntime.adapter("hub", "resource") as ResourceHubAdapter).delete(it.canonicalId)
            }
            initializeHubContextRuntime(context)
            WorkflowyIntegrationSettings.setEnabled(context, initial.enabled)
        }
    }

    private class QaTimerAlertAdapter : HubEntityAdapter {
        override val moduleId = "timer"
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
