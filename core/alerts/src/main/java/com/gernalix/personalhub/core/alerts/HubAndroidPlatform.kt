@file:android.annotation.SuppressLint("MissingPermission")

package com.gernalix.personalhub.core.alerts

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Shared Android notification/alarm plumbing for PersonalHub features.
 *
 * Feature modules keep ownership of domain rules, text, icons, receivers and channel semantics.
 * This layer owns only the platform mechanics that otherwise tend to drift between modules.
 */
object HubNotificationPlatform {
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun pendingIntentFlags(base: Int = PendingIntent.FLAG_UPDATE_CURRENT): Int =
        base or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

    fun ensureChannel(
        context: Context,
        id: String,
        name: CharSequence,
        importance: Int,
        description: String? = null,
        configure: NotificationChannel.() -> Unit = {},
    ): NotificationChannel? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        manager.getNotificationChannel(id)?.let { return it }
        val channel = NotificationChannel(id, name, importance).apply {
            this.description = description
            configure()
        }
        manager.createNotificationChannel(channel)
        return channel
    }

    fun post(context: Context, notificationId: Int, notification: Notification): Boolean {
        if (!canPost(context)) return false
        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
}

enum class HubAlarmPrecision {
    INEXACT,
    ALLOW_WHILE_IDLE,
    EXACT,
}

object HubAlarmPlatform {
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /**
     * Schedule an RTC_WAKEUP alarm with one consistent fallback policy.
     *
     * [showIntent] is required only when an exact alarm must use AlarmClock semantics, either
     * explicitly through [alarmStyle] or because exact-alarm permission is denied.
     */
    fun schedule(
        context: Context,
        triggerAtMs: Long,
        operation: PendingIntent,
        precision: HubAlarmPrecision,
        showIntent: PendingIntent? = null,
        alarmStyle: Boolean = false,
        exactFallbackWindowMs: Long = 30_000L,
    ): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching {
            when (precision) {
                HubAlarmPrecision.INEXACT -> {
                    manager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
                }

                HubAlarmPrecision.ALLOW_WHILE_IDLE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
                    } else {
                        manager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
                    }
                }

                HubAlarmPrecision.EXACT -> {
                    val exactDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !manager.canScheduleExactAlarms()
                    if (alarmStyle || exactDenied) {
                        val visibleIntent = requireNotNull(showIntent) {
                            "showIntent is required for AlarmClock scheduling"
                        }
                        AlarmManagerCompat.setAlarmClock(
                            manager,
                            triggerAtMs,
                            visibleIntent,
                            operation,
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        manager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            operation,
                        )
                    } else {
                        manager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
                    }
                }
            }
            true
        }.recoverCatching { error ->
            if (
                precision == HubAlarmPrecision.EXACT &&
                !alarmStyle &&
                showIntent != null &&
                error is SecurityException
            ) {
                AlarmManagerCompat.setAlarmClock(manager, triggerAtMs, showIntent, operation)
                true
            } else if (precision == HubAlarmPrecision.EXACT) {
                manager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    exactFallbackWindowMs,
                    operation,
                )
                true
            } else {
                throw error
            }
        }.getOrDefault(false)
    }

    fun cancel(
        context: Context,
        operation: PendingIntent,
        cancelPendingIntent: Boolean = true,
    ) {
        context.getSystemService(AlarmManager::class.java)?.cancel(operation)
        if (cancelPendingIntent) operation.cancel()
    }
}
