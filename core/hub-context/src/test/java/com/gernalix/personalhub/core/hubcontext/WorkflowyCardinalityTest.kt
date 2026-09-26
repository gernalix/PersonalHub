package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkflowyCardinalityTest {
    @Test fun genericComposerCannotBypassOneLinkPerEntity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkflowyIntegrationSettings.setEnabled(context, true)
        val resourceAdapter = ResourceHubAdapter(context)
        HubContextRuntime.initialize(context, listOf(FakeAnchor(), resourceAdapter))
        val anchor = HubEntityRef("people", "person", UUID.randomUUID().toString())
        val first = requireNotNull(resourceAdapter.create(HubCreateRequest(
            "Workflowy", mapOf("kind" to HubResourceKinds.WEB_URL,
                "value" to "https://workflowy.com/#/59d823cea257"))))
        val second = requireNotNull(resourceAdapter.create(HubCreateRequest(
            "Workflowy", mapOf("kind" to HubResourceKinds.WEB_URL,
                "value" to "https://workflowy.com/#/59d823cea258"))))
        val contextId = HubContextRuntime.createContext(listOf(anchor to "", first.ref to ""), title = "Workflowy")
        try {
            assertEquals("https://workflowy.com/#/59d823cea257", WorkflowyHubBridge.notificationUrl(context, anchor))
            assertTrue(runCatching {
                HubContextRuntime.createContext(listOf(anchor to "", second.ref to ""), title = "Workflowy")
            }.isFailure)
            assertEquals(1, HubContextRuntime.contexts(anchor).size)
            WorkflowyIntegrationSettings.setEnabled(context, false)
            assertFalse(WorkflowyIntegrationSettings.isEnabled(context))
            assertEquals(null, WorkflowyHubBridge.notificationUrl(context, anchor))
            assertTrue(runCatching {
                WorkflowyHubBridge.attachUrl(context, anchor, "https://workflowy.com/#/59d823cea259")
            }.isFailure)
            WorkflowyIntegrationSettings.setEnabled(context, true)
            assertEquals(1, HubContextRuntime.contexts(anchor).size)
        } finally {
            WorkflowyIntegrationSettings.setEnabled(context, true)
            HubContextRuntime.deleteContext(contextId)
            val manual = WorkflowyHubBridge.attachUrl(context, anchor,
                "https://workflowy.com/#/59d823cea259")
            assertEquals(1, HubContextRuntime.contexts(anchor).size)
            assertTrue(runCatching {
                WorkflowyHubBridge.attachUrl(context, anchor, "https://workflowy.com/#/59d823cea260")
            }.isFailure)
            WorkflowyHubBridge.delink(context, anchor, manual.ref)
            assertTrue(HubContextRuntime.contexts(anchor).isEmpty())
            assertEquals(null, WorkflowyHubBridge.notificationUrl(context, anchor))
            resourceAdapter.delete(manual.ref.canonicalId)
            resourceAdapter.delete(first.ref.canonicalId)
            resourceAdapter.delete(second.ref.canonicalId)
            WorkflowyIntegrationSettings.setEnabled(context, false)
        }
    }

    private class FakeAnchor : HubEntityAdapter {
        override val moduleId = "people"
        override val entityKind = "person"
        override val capabilities = setOf("person")
        override suspend fun exists(canonicalId: String) = true
        override suspend fun lifecycle(canonicalId: String) = HubEntityLifecycle.ACTIVE
        override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.associateWith { id ->
            HubEntitySummary(HubEntityRef(moduleId, entityKind, id), "QA person")
        }
        override suspend fun search(query: String, limit: Int) = emptyList<HubEntitySummary>()
        override suspend fun openTarget(canonicalId: String): HubOpenTarget? = null
    }
}
