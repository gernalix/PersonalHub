package com.gernalix.personalhub.core.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertLinkPolicyTest {
    @Test
    fun acceptsOnlyWholeSafeUris() {
        assertEquals("https", AlertLinkPolicy.linkOnlyUriOrNull(" https://example.com/a ")?.scheme)
        assertEquals("workflowy", AlertLinkPolicy.linkOnlyUriOrNull("workflowy://workflowy.com/#/abc")?.scheme)
        assertNull(AlertLinkPolicy.linkOnlyUriOrNull("Open https://example.com"))
        assertNull(AlertLinkPolicy.linkOnlyUriOrNull("intent://example/#Intent;end"))
        assertNull(AlertLinkPolicy.linkOnlyUriOrNull("file:///tmp/test"))
    }
}
