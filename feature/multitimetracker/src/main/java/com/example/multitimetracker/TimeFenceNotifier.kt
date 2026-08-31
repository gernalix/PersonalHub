package com.example.multitimetracker

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.multitimetracker.model.TimedTagNotificationType

object TimeFenceNotifier {

    // IMPORTANT: Changing the channel id forces Android to re-create channel settings.
    // Users might have disabled "pop on screen" for older channels, which we cannot override.
    const val CHANNEL_ID = "time_fence_critical"
    private const val LEGACY_TIMED_SESSION_NORMAL_CHANNEL_ID = "timed_session_normal"
    private const val LEGACY_TIMED_SESSION_ALARM_CHANNEL_ID = "timed_session_alarm"
    private const val TIMED_SESSION_NORMAL_CHANNEL_ID = "timed_session_normal_v2"
    private const val TIMED_SESSION_ALARM_CHANNEL_ID = "timed_session_alarm_v2"
    private const val TIMED_SESSION_DEDUPE_WINDOW_MS = 5_000L
    private val recentTimedSessionNotifications = LinkedHashMap<Int, Long>()

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(false)
    }

    fun openFullScreenIntentSettings(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = packageUri
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = packageUri
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(notificationId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        }
    }

    fun acknowledgeTimedSession(context: Context, notificationId: Int) {
        cancelNotification(context, notificationId)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val alarmSound: Uri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        val notificationSound: Uri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val notificationAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.time_fence_critical_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.time_fence_critical_channel_description)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 120, 250, 120, 450)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                setSound(alarmSound, alarmAttrs)
            }
            nm.createNotificationChannel(channel)
        }
        if (nm.getNotificationChannel(TIMED_SESSION_NORMAL_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    TIMED_SESSION_NORMAL_CHANNEL_ID,
                    context.getString(R.string.timed_session_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.timed_session_channel_description)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 120, 250)
                    enableLights(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setShowBadge(true)
                    setSound(notificationSound, notificationAttrs)
                }
            )
        }
        if (nm.getNotificationChannel(TIMED_SESSION_ALARM_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    TIMED_SESSION_ALARM_CHANNEL_ID,
                    context.getString(R.string.timed_session_alarm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.timed_session_alarm_channel_description)
                    enableVibration(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setSound(alarmSound, alarmAttrs)
                }
            )
        }
    }

    fun notify(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        // Kept for forward-compat with older patches; defaults match the desired behavior.
        headsUpEnabled: Boolean = true,
        criticalFullScreenEnabled: Boolean = true,
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // Content + full-screen intent to maximize visibility.
        val fullIntent = Intent(context, TimeFenceFullScreenActivity::class.java).apply {
            putExtra(TimeFenceFullScreenActivity.EXTRA_TITLE, title)
            putExtra(TimeFenceFullScreenActivity.EXTRA_MESSAGE, message)
            putExtra(TimeFenceFullScreenActivity.EXTRA_NOTIFICATION_ID, notificationId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPi = PendingIntent.getActivity(context, notificationId, fullIntent, piFlags)
        val canShowFullScreen = criticalFullScreenEnabled && canUseFullScreenIntent(context)

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(if (headsUpEnabled) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPi)
            .setFullScreenIntent(contentPi, canShowFullScreen)
            .setAutoCancel(true)
            .build()

        nm.notify(notificationId, n)
    }

    fun notifyTimedSession(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        notificationType: TimedTagNotificationType,
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (shouldSkipTimedSessionNotification(notificationId)) return
        if (hasActiveTimedSessionNotification(nm, notificationId)) return
        val isAlarm = notificationType == TimedTagNotificationType.ALARM
        val channelId = if (isAlarm) TIMED_SESSION_ALARM_CHANNEL_ID else TIMED_SESSION_NORMAL_CHANNEL_ID
        val canShowFullScreen = isAlarm && canUseFullScreenIntent(context)

        val fullIntent = Intent(context, TimeFenceFullScreenActivity::class.java).apply {
            putExtra(TimeFenceFullScreenActivity.EXTRA_TITLE, title)
            putExtra(TimeFenceFullScreenActivity.EXTRA_MESSAGE, message)
            putExtra(TimeFenceFullScreenActivity.EXTRA_NOTIFICATION_ID, notificationId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPi = PendingIntent.getActivity(context, notificationId, fullIntent, piFlags)
        val acknowledgeIntent = Intent(context, TimeFenceTimerReceiver::class.java).apply {
            action = TimeFenceTimerReceiver.ACTION_ACK_TIMED_SESSION
            putExtra(TimeFenceTimerReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val acknowledgePi = PendingIntent.getBroadcast(context, notificationId, acknowledgeIntent, piFlags)
        val category = if (isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(category)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPi)
            .setFullScreenIntent(contentPi, canShowFullScreen)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.ok), acknowledgePi)
            .build()

        nm.notify(notificationId, notification)
    }

    @Synchronized
    private fun shouldSkipTimedSessionNotification(notificationId: Int): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        recentTimedSessionNotifications.entries.removeAll { nowElapsed - it.value > TIMED_SESSION_DEDUPE_WINDOW_MS }
        val lastShownAt = recentTimedSessionNotifications[notificationId]
        if (lastShownAt != null && nowElapsed - lastShownAt <= TIMED_SESSION_DEDUPE_WINDOW_MS) {
            return true
        }
        recentTimedSessionNotifications[notificationId] = nowElapsed
        return false
    }

    private fun hasActiveTimedSessionNotification(nm: NotificationManager, notificationId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return runCatching {
            nm.activeNotifications.any { sbn ->
                if (sbn.id != notificationId) return@any false
                val channelId = sbn.notification.channelId
                channelId == LEGACY_TIMED_SESSION_NORMAL_CHANNEL_ID ||
                    channelId == LEGACY_TIMED_SESSION_ALARM_CHANNEL_ID ||
                    channelId == TIMED_SESSION_NORMAL_CHANNEL_ID ||
                    channelId == TIMED_SESSION_ALARM_CHANNEL_ID
            }
        }.getOrDefault(false)
    }
}
