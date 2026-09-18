@file:android.annotation.SuppressLint("MissingPermission")

package com.gernalix.personalhub.core.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object AlertNotificationDispatcher {
    const val CHANNEL_ID = "personalhub_alerts_v1"

    fun post(
        context: Context,
        notificationId: Int,
        fire: AlertFire,
        emitTaskerBroadcast: Boolean = false,
    ): Boolean {
        val posted = postNotification(
            context = context,
            notificationId = notificationId,
            title = fire.title,
            message = fire.message,
        )
        if (posted && emitTaskerBroadcast) AlertTaskerBridge.emit(context, fire)
        return posted
    }

    fun postNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(context, manager)
        val contentIntent = contentPendingIntent(context, notificationId, message)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(notificationId, notification)
        return true
    }

    private fun contentPendingIntent(context: Context, notificationId: Int, message: String): PendingIntent {
        val link = AlertLinkPolicy.linkOnlyUriOrNull(message)
        val intent = if (link != null) {
            Intent(Intent.ACTION_VIEW, link).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val workflowy = link.scheme.equals("workflowy", ignoreCase = true) ||
                    (link.scheme.equals("https", ignoreCase = true) &&
                        link.host.equals("workflowy.com", ignoreCase = true))
                if (workflowy && isPackageInstalled(context, WORKFLOWY_PACKAGE)) {
                    setPackage(WORKFLOWY_PACKAGE)
                }
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, notificationId, intent, flags)
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private const val WORKFLOWY_PACKAGE = "com.workflowy.android"

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PersonalHub alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Timer and Places alerts"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
        )
    }
}

/**
 * Optional Tasker bridge. It is not part of matching or persistence and is disabled by default.
 */
object AlertTaskerBridge {
    const val ACTION_ALERT_FIRED = "com.gernalix.personalhub.ALERT_FIRED"

    fun emit(context: Context, fire: AlertFire) {
        context.sendBroadcast(
            Intent(ACTION_ALERT_FIRED).apply {
                putExtra("rule_id", fire.ruleId)
                putExtra("domain", fire.domain.name)
                putExtra("trigger", fire.trigger.name)
                putExtra("entity_id", fire.entityId)
                putExtra("tag_ids", fire.tagIds.toTypedArray())
                putExtra("title", fire.title)
                putExtra("message", fire.message)
                putExtra("fired_at_ms", fire.firedAtMs)
            }
        )
    }
}
