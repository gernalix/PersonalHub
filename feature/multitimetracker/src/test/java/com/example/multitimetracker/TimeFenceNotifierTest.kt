package com.example.multitimetracker

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeFenceNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun timerAlertNotificationIsSimpleReminderWithoutFullScreenIntent() {
        TimeFenceNotifier.notify(
            context = context,
            notificationId = 42,
            title = "Timer",
            message = "plain reminder",
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).getNotification(42)

        assertEquals(TimeFenceNotifier.TIMER_ALERT_CHANNEL_ID, notification.channelId)
        assertEquals(null, notification.fullScreenIntent)
        assertEquals(Intent.ACTION_VIEW, shadowOf(notification.contentIntent).savedIntent.action)
    }

    @Test
    fun exactHttpOnlyTextRoutesNotificationTapThroughDispatcher() {
        val url = "https://example.com/timer?a=1"

        val intent = TimeFenceNotifier.timerAlertContentIntent(context, 7, "  $url  ")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(url), intent.data)
        assertEquals("com.example.multitimetracker.TimerAlertLinkDispatcherActivity", intent.component?.className)
    }

    @Test
    fun exactWorkflowyMessageRoutesNotificationTapThroughDispatcher() {
        val url = "https://workflowy.com/#/a3b177f21c87"

        val intent = TimeFenceNotifier.timerAlertContentIntent(context, 12, url)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(url), intent.data)
        assertEquals("com.example.multitimetracker.TimerAlertLinkDispatcherActivity", intent.component?.className)
    }

    @Test
    fun exactCustomDeepLinkOnlyTextRoutesWithoutResolvePrecheck() {
        val uri = "otherapp://timer/123"

        val intent = TimeFenceNotifier.timerAlertContentIntent(context, 8, uri)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(uri), intent.data)
        assertEquals("com.example.multitimetracker.TimerAlertLinkDispatcherActivity", intent.component?.className)
    }

    @Test
    fun mixedTextAndUrlUsesTimerFallback() {
        val intent = TimeFenceNotifier.timerAlertContentIntent(context, 9, "testo https://example.com")

        assertEquals("mtt://time-fence-alert/9", intent.data.toString())
        assertEquals("com.example.multitimetracker.MainActivity", intent.component?.className)
    }

    @Test
    fun malformedUriUsesTimerFallbackButUnhandledAbsoluteUriUsesDispatcher() {
        val malformed = TimeFenceNotifier.timerAlertContentIntent(context, 10, "https://")
        val unhandled = TimeFenceNotifier.timerAlertContentIntent(context, 11, "unknownscheme://timer/1")

        assertEquals("mtt://time-fence-alert/10", malformed.data.toString())
        assertEquals(Uri.parse("unknownscheme://timer/1"), unhandled.data)
        assertEquals("com.example.multitimetracker.TimerAlertLinkDispatcherActivity", unhandled.component?.className)
    }

    @Test
    fun differentLinkAlertsKeepDistinctPendingIntentDestinations() {
        val first = "https://example.com/one"
        val second = "otherapp://timer/two"

        TimeFenceNotifier.notify(context, 21, "Timer", first)
        TimeFenceNotifier.notify(context, 22, "Timer", second)

        val manager = context.getSystemService(NotificationManager::class.java)
        val firstIntent = shadowOf(shadowOf(manager).getNotification(21).contentIntent).savedIntent
        val secondIntent = shadowOf(shadowOf(manager).getNotification(22).contentIntent).savedIntent

        assertEquals(Uri.parse(first), firstIntent.data)
        assertEquals(Uri.parse(second), secondIntent.data)
    }

    @Test
    fun linkParserRejectsPlainAndMixedText() {
        assertNull(TimeFenceNotifier.timerAlertLinkIntentOrNull(context, "plain reminder"))
        assertNull(TimeFenceNotifier.timerAlertLinkIntentOrNull(context, "https://example.com more"))
    }
}
