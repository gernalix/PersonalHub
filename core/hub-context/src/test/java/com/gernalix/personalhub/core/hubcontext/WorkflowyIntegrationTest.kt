package com.gernalix.personalhub.core.hubcontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
