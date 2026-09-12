package com.gernalix.personalhub

import android.net.Uri
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.HubEntityRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HubDeepLinkContractTest {
    @Test
    fun entityPermalinkRoundTripsEncodedCanonicalId() {
        val ref = HubEntityRef("people", "person", "id/with spaces")
        val uri = HubDeepLinkContract.entityUri(ref, HubDeepLinkContract.ACTION_EDIT)

        val result = HubDeepLinkContract.parse(uri)
        assertTrue(result.isSuccess)
        val target = result.target as HubDeepLinkContract.EntityTarget
        assertEquals(ref, target.ref)
        assertEquals(HubDeepLinkContract.ACTION_EDIT, target.action)
    }

    @Test
    fun contextAndEventPermalinksRoundTrip() {
        val context = HubDeepLinkContract.parse(HubDeepLinkContract.contextUri("ctx-1")).target
        val event = HubDeepLinkContract.parse(HubDeepLinkContract.eventUri("evt-1")).target
        assertEquals("ctx-1", (context as HubDeepLinkContract.ContextTarget).contextId)
        assertEquals("evt-1", (event as HubDeepLinkContract.EventTarget).eventId)
    }

    @Test
    fun searchPreservesIsoRangeAndRepeatedModules() {
        val uri = HubDeepLinkContract.searchUri(
            "2026-09-11T11:15:00+02:00",
            "2026-09-11T11:49:00+02:00",
            listOf("places", "people", "places"),
        )
        val target = HubDeepLinkContract.parse(uri).target as HubDeepLinkContract.SearchTarget
        assertEquals("2026-09-11T11:15:00+02:00", target.fromIso)
        assertEquals("2026-09-11T11:49:00+02:00", target.toIso)
        assertEquals(listOf("people", "places"), target.modules)
    }

    @Test
    fun parserRejectsUnknownVersionAndDestructiveAction() {
        val old = HubDeepLinkContract.parse(Uri.parse("personalhub://entity/v2/people/person/48"))
        assertNull(old.target)
        assertEquals(HubDeepLinkContract.ParseError.UNSUPPORTED_VERSION, old.error)

        val destructive = HubDeepLinkContract.parse(Uri.parse("personalhub://entity/v1/people/person/48?action=delete"))
        assertNull(destructive.target)
        assertEquals(HubDeepLinkContract.ParseError.UNSUPPORTED_ACTION, destructive.error)
    }
}
