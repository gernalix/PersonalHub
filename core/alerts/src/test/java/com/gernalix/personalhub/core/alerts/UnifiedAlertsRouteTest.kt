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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnifiedAlertsRouteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun homeTimerAndPlacesUseOneAlertsRoute() {
        assertEquals(UnifiedAlertsRoute.Request(UnifiedAlertsRoute.Filter.ALL),
            UnifiedAlertsRoute.parse(UnifiedAlertsRoute.intent(context)))
        assertEquals(UnifiedAlertsRoute.Request(UnifiedAlertsRoute.Filter.TIMER),
            UnifiedAlertsRoute.parse(UnifiedAlertsRoute.intent(context, UnifiedAlertsRoute.Filter.TIMER)))
        assertEquals(UnifiedAlertsRoute.Request(UnifiedAlertsRoute.Filter.PLACES, "place-1"),
            UnifiedAlertsRoute.parse(UnifiedAlertsRoute.intent(context, UnifiedAlertsRoute.Filter.PLACES, "place-1")))
    }

    @Test fun unrelatedTimerDeepLinksDoNotOpenAlerts() {
        assertNull(UnifiedAlertsRoute.parse(Intent(Intent.ACTION_VIEW, Uri.parse("personalhub://module/timer?sessionId=1"))))
        assertNull(UnifiedAlertsRoute.parse(Intent(Intent.ACTION_VIEW, Uri.parse("personalhub://module/places?alerts=all"))))
    }
}
