package com.gernalix.sostanze.notifications

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gernalix.sostanze.R
import com.gernalix.sostanze.domain.NotificationPlan
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

@SuppressLint("MissingPermission")
class SostanzeNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SostanzeNotificationScheduler.ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: context.getString(R.string.app_name)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: context.getString(R.string.notification_default_text)
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, title.hashCode())
        val notification = NotificationCompat.Builder(context, SostanzeNotificationScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    id,
                    Intent().setClassName(context.packageName, "com.gernalix.sostanze.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}

class SostanzeNotificationRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = PersonalHubDatabase.get(context).dao()
                val now = System.currentTimeMillis()
                dao.deleteExpiredNotificationState(now)
                dao.allNotificationState().forEach { state ->
                    SostanzeNotificationScheduler.schedule(
                        context,
                        NotificationPlan(state.kind, state.entityId, state.scheduledForMs),
                        state.entityId.toString(),
                    )
                }
                SostanzeNotificationScheduler.restoreRandomAlerts(context)
            } finally { pending.finish() }
        }
    }
}

object SostanzeNotificationScheduler {
    const val CHANNEL_ID = "sostanze_reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun schedule(context: Context, plan: NotificationPlan, label: String) {
        if (plan.scheduledForMs <= System.currentTimeMillis()) return
        ensureChannel(context)
        val title = when (plan.kind) {
            "interaction_end" -> context.getString(R.string.notification_interaction_end)
            "refill" -> context.getString(R.string.notification_refill)
            "missed_dose" -> context.getString(R.string.notification_missed_dose)
            else -> context.getString(R.string.app_name)
        }
        val text = when (plan.kind) {
            "interaction_end" -> context.getString(R.string.notification_available_again, label)
            "refill" -> context.getString(R.string.notification_refill_text, label)
            "missed_dose" -> context.getString(R.string.notification_missed_text, label)
            else -> label
        }
        val identity = "${plan.kind}:${plan.entityId}:${plan.scheduledForMs}"
        val intent = Intent(context, SostanzeNotificationReceiver::class.java)
            .setAction("com.gernalix.sostanze.NOTIFY.$identity")
            .setData(android.net.Uri.parse("personalhub://sostanze/notification/$identity"))
            .putExtra(SostanzeNotificationReceiver.EXTRA_TITLE, title)
            .putExtra(SostanzeNotificationReceiver.EXTRA_TEXT, text)
            .putExtra(SostanzeNotificationReceiver.EXTRA_NOTIFICATION_ID, plan.hashCode())
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            identity.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.set(AlarmManager.RTC_WAKEUP, plan.scheduledForMs, pendingIntent)
    }

    fun saveRandomAlertConfig(
        context: Context,
        substanceId: Long,
        label: String,
        enabled: Boolean,
        count: Int,
        window: SostanzeRandomAlertWindow,
        nowMs: Long = System.currentTimeMillis(),
        nextOffsetMs: (Long) -> Long = { maxMs -> Random.nextLong(1L, maxMs + 1L) },
    ) {
        val old = SostanzeRandomAlertStore.read(context, substanceId, label)
        old.scheduledAtMs.filter { it > nowMs }.forEach { cancelRandomAlert(context, old.identity, it) }
        val scheduled = if (enabled && count > 0) {
            planSostanzeRandomAlertInstants(nowMs, count, window, nextOffsetMs)
        } else {
            emptyList()
        }
        val config = SostanzeRandomAlertConfig(
            substanceId = substanceId,
            label = label,
            enabled = enabled,
            count = count.coerceAtLeast(0),
            window = window,
            scheduledAtMs = scheduled,
        )
        SostanzeRandomAlertStore.write(context, config)
        scheduled.forEach { scheduleRandomAlert(context, config.identity, it, label) }
    }

    fun restoreRandomAlerts(context: Context, nowMs: Long = System.currentTimeMillis()) {
        SostanzeRandomAlertStore.all(context)
            .filter { it.enabled && it.count > 0 }
            .forEach { config ->
                val future = config.scheduledAtMs.filter { it > nowMs }
                val scheduled = future.ifEmpty {
                    planSostanzeRandomAlertInstants(nowMs, config.count, config.window) { maxMs ->
                        Random.nextLong(1L, maxMs + 1L)
                    }
                }
                if (scheduled != config.scheduledAtMs) SostanzeRandomAlertStore.write(context, config.copy(scheduledAtMs = scheduled))
                scheduled.filter { it > nowMs }.forEach { scheduleRandomAlert(context, config.identity, it, config.label) }
            }
    }

    private fun scheduleRandomAlert(context: Context, identity: String, fireAtMs: Long, label: String) {
        if (fireAtMs <= System.currentTimeMillis()) return
        val intent = Intent(context, SostanzeNotificationReceiver::class.java)
            .setAction("com.gernalix.sostanze.RANDOM_ALERT.$identity.$fireAtMs")
            .setData(android.net.Uri.parse("personalhub://sostanze/random-alert/$identity/$fireAtMs"))
            .putExtra(SostanzeNotificationReceiver.EXTRA_TITLE, context.getString(R.string.random_alert_sostanze_title))
            .putExtra(SostanzeNotificationReceiver.EXTRA_TEXT, label)
            .putExtra(SostanzeNotificationReceiver.EXTRA_NOTIFICATION_ID, identity.hashCode() xor fireAtMs.hashCode())
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            identity.hashCode() xor fireAtMs.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.RTC_WAKEUP, fireAtMs, pendingIntent)
    }

    private fun cancelRandomAlert(context: Context, identity: String, fireAtMs: Long) {
        val intent = Intent(context, SostanzeNotificationReceiver::class.java)
            .setAction("com.gernalix.sostanze.RANDOM_ALERT.$identity.$fireAtMs")
            .setData(android.net.Uri.parse("personalhub://sostanze/random-alert/$identity/$fireAtMs"))
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            identity.hashCode() xor fireAtMs.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pendingIntent)
        pendingIntent.cancel()
    }
}
