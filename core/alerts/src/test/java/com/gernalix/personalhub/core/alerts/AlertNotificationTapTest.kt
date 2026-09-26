package com.gernalix.personalhub.core.alerts

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlertNotificationTapTest {
    @Test fun linkedWorkflowyNodeOverridesAlertMessage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    }
}
