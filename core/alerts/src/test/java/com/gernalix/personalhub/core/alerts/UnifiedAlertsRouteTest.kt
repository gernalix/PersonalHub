package com.gernalix.personalhub.core.alerts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UnifiedAlertsRouteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun homeTimerAndPlacesUseOneAlertsRoute() {
        assertEquals(UnifiedAlertsRoute.Request(null),
            UnifiedAlertsRoute.parse(UnifiedAlertsRoute.intent(context)))
        assertEquals(UnifiedAlertsRoute.Request(AlertDomain.TIMER),
            UnifiedAlertsRoute.parse(UnifiedAlertsRoute.intent(context, AlertDomain.TIMER)))
        assertEquals(UnifiedAlertsRoute.Request(AlertDomain.PLACE, "place-1"),
            UnifiedAlertsRoute.parse(UnifiedAlertsRoute.intent(context, AlertDomain.PLACE, "place-1")))
    }

    @Test fun unrelatedTimerDeepLinksDoNotOpenAlerts() {
        assertNull(UnifiedAlertsRoute.parse(Intent(Intent.ACTION_VIEW, Uri.parse("personalhub://module/timer?sessionId=1"))))
        assertNull(UnifiedAlertsRoute.parse(Intent(Intent.ACTION_VIEW, Uri.parse("personalhub://module/places?alerts=all"))))
    }
}
