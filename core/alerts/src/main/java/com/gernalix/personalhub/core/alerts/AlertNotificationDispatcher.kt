package com.gernalix.personalhub.core.alerts

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
        ensureChannel(context)
        val contentIntent = contentPendingIntent(
            context = context,
            notificationId = notificationId,
            message = message,
            fallbackIntent = launchAppIntent(context),
        )

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

        return HubNotificationPlatform.post(context, notificationId, notification)
    }

    /**
     * Shared tap policy used by Timer and Places notifications.
     *
     * If [message] is exactly one supported link, the PendingIntent opens that URI directly.
     * Otherwise [fallbackIntent] is used.
     */
    fun contentPendingIntent(
        context: Context,
        notificationId: Int,
        message: String,
        fallbackIntent: Intent? = null,
    ): PendingIntent {
        val chosen = directLinkIntentOrNull(context, message)
            ?: fallbackIntent
            ?: launchAppIntent(context)

        return PendingIntent.getActivity(
            context,
            notificationId,
            chosen,
            HubNotificationPlatform.pendingIntentFlags(),
        )
    }

    fun directLinkIntentOrNull(context: Context, message: String): Intent? {
        val link = AlertLinkPolicy.linkOnlyUriOrNull(message) ?: return null
        val base = Intent(Intent.ACTION_VIEW, link).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val workflowyLink = link.scheme.equals("workflowy", ignoreCase = true) ||
            (link.scheme.equals("https", ignoreCase = true) &&
                link.host.equals("workflowy.com", ignoreCase = true))
        if (workflowyLink) {
            val workflowyIntent = Intent(base).setPackage(WORKFLOWY_PACKAGE)
            if (workflowyIntent.resolveActivity(context.packageManager) != null) {
                return workflowyIntent
            }
        }
        return base.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    private fun launchAppIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)

    private const val WORKFLOWY_PACKAGE = "com.workflowy.android"

    private fun ensureChannel(context: Context) {
        HubNotificationPlatform.ensureChannel(
            context = context,
            id = CHANNEL_ID,
            name = "PersonalHub alerts",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Timer and Places alerts",
        ) {
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
    }
}

/**
 * Optional Tasker bridge. PH has no Tasker dependency: the explicit broadcast is inert unless the
 * user has Tasker installed and creates a profile for ACTION_ALERT_FIRED.
 */
object AlertTaskerBridge {
    const val ACTION_ALERT_FIRED = "com.gernalix.personalhub.ALERT_FIRED"
    private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"

    fun emit(context: Context, fire: AlertFire) {
        context.sendBroadcast(
            Intent(ACTION_ALERT_FIRED).setPackage(TASKER_PACKAGE).apply {
                putExtra("rule_id", fire.ruleId)
                putExtra("domain", fire.domain.name)
                putExtra("trigger", fire.trigger.name)
                putExtra("entity_id", fire.entityId)
                putExtra("tag_ids", fire.tagIds.toTypedArray())
                putExtra("tags", fire.tagNames.toTypedArray())
                putExtra("title", fire.title)
                putExtra("message", fire.message)
                putExtra("fired_at_ms", fire.firedAtMs)
            }
        )
    }
}
