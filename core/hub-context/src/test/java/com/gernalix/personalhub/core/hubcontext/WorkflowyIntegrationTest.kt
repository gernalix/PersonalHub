package com.gernalix.personalhub.core.hubcontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkflowyIntegrationTest {
    @Test
    fun workflowyUrlsAreStrictlyValidated() {
        assertEquals(
            "https://workflowy.com/#/abc123",
            WorkflowyLinkPolicy.normalize(" https://workflowy.com/#/abc123 "),
        )
        assertEquals(
            "https://beta.workflowy.com/#/abc123",
            WorkflowyLinkPolicy.normalize("https://beta.workflowy.com/#/abc123"),
        )
        assertNull(WorkflowyLinkPolicy.normalize("https://workflowy.com.evil.example/#/abc123"))
        assertNull(WorkflowyLinkPolicy.normalize("javascript:https://workflowy.com/#/abc123"))
    }

    @Test
    fun sharedTextExtractsOnlyWorkflowyLink() {
        assertEquals(
            "https://workflowy.com/#/59d823cea257",
            WorkflowyLinkPolicy.extractFromSharedText(
                "My node: https://workflowy.com/#/59d823cea257",
            ),
        )
        assertNull(WorkflowyLinkPolicy.extractFromSharedText("https://example.com/#/59d823cea257"))
    }

    @Test
    fun fullWorkflowyIdProducesCanonicalShortDeepLink() {
        assertEquals(
            "https://workflowy.com/#/59d823cea257",
            WorkflowyLinkPolicy.deepLink("2af3dd5c-7b2e-5248-bbee-59d823cea257"),
        )
    }

    @Test
    fun createResponseRequiresItemId() {
        assertEquals(
            "2af3dd5c-7b2e-5248-bbee-59d823cea257",
            WorkflowyApiClient.parseCreatedNodeId(
                """{"item_id":"2af3dd5c-7b2e-5248-bbee-59d823cea257"}""",
            ),
        )
        runCatching { WorkflowyApiClient.parseCreatedNodeId("{}") }
            .onSuccess { error("Expected invalid response to fail") }
    }

    @Test
    fun targetPolicyAcceptsTodayAndRejectsWhitespace() {
        assertTrue(WorkflowyIntegrationSettings.validTarget("today"))
        assertTrue(WorkflowyIntegrationSettings.validTarget("my-shortcut"))
        assertFalse(WorkflowyIntegrationSettings.validTarget("two words"))
        assertFalse(WorkflowyIntegrationSettings.validTarget(""))
    }

    @Test
    fun linkActionsAndNodeIdentityAreStrict() {
        assertEquals(listOf("Link node (auto)", "Link node (manual)"), WorkflowyHubBridge.actionNames(0))
        assertEquals(listOf("Open node", "Delink node", "Delete node"), WorkflowyHubBridge.actionNames(1))
        assertEquals("59d823cea257", WorkflowyLinkPolicy.shortId("https://workflowy.com/#/59d823cea257"))
        assertNull(WorkflowyLinkPolicy.shortId("https://workflowy.com/#/wrong"))
        // Recovered from f66ce663: reject non-node, nested and lookalike links.
        assertNull(WorkflowyLinkPolicy.shortId("https://workflowy.com/#/today"))
        assertNull(WorkflowyLinkPolicy.shortId("https://workflowy.com/#/59d823cea257/child"))
        assertNull(WorkflowyLinkPolicy.shortId("https://workflowy.com.evil.example/#/59d823cea257"))
        assertEquals(
            "2af3dd5c-7b2e-5248-bbee-59d823cea257",
            WorkflowyApiClient.parseResolvedNodeId(
                """{"node":{"id":"2af3dd5c-7b2e-5248-bbee-59d823cea257"}}""",
                "59d823cea257",
            ),
        )
        assertTrue(runCatching {
            WorkflowyApiClient.parseResolvedNodeId(
                """{"node":{"id":"2af3dd5c-7b2e-5248-bbee-59d823cea257"}}""",
                "aaaaaaaaaaaa",
            )
        }.isFailure)
    }

    @Test
    fun globalGatePersistsWithoutDiscardingConfiguration() {
        val context = RuntimeEnvironment.getApplication()
        WorkflowyIntegrationSettings.setEnabled(context, false)
        assertFalse(WorkflowyIntegrationSettings.isEnabled(context))
        WorkflowyIntegrationSettings.setEnabled(context, true)
        assertTrue(WorkflowyIntegrationSettings.configuration(context).enabled)
        WorkflowyIntegrationSettings.setEnabled(context, false)
        assertFalse(WorkflowyIntegrationSettings.configuration(context).enabled)
        assertEquals(WorkflowyIntegrationSettings.DEFAULT_TARGET, WorkflowyIntegrationSettings.target(context))
        assertFalse(WorkflowyHubBridge.open(context, "https://workflowy.com/#/59d823cea257"))
    }

    @Test
    fun remoteDeleteFailureKeepsLocalLink() = runBlocking {
        var localLinkPresent = true
        val failure = runCatching {
            WorkflowyHubBridge.deleteRemoteThenLocal(
                remote = { error("remote failed") },
                local = { localLinkPresent = false },
            )
        }
        assertTrue(failure.isFailure)
        assertTrue(localLinkPresent)
        WorkflowyHubBridge.deleteRemoteThenLocal(
            remote = {},
            local = { localLinkPresent = false },
        )
        assertFalse(localLinkPresent)
    }

    @Test
    fun autoCreatesEmptyNodeUnderToday() {
        val body = JSONObject(WorkflowyApiClient.nodeBody("", "other-target", allowEmpty = true))
        assertEquals("today", body.getString("parent_id"))
        assertEquals("", body.getString("name"))
        assertEquals("top", body.getString("position"))
    }


}
