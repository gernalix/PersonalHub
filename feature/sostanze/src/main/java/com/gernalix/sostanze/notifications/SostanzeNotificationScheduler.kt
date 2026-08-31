package com.gernalix.sostanze.notifications

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
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
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
        val intent = Intent(context, SostanzeNotificationReceiver::class.java)
            .putExtra(SostanzeNotificationReceiver.EXTRA_TITLE, title)
            .putExtra(SostanzeNotificationReceiver.EXTRA_TEXT, text)
            .putExtra(SostanzeNotificationReceiver.EXTRA_NOTIFICATION_ID, plan.hashCode())
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            plan.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.set(AlarmManager.RTC_WAKEUP, plan.scheduledForMs, pendingIntent)
    }
}
