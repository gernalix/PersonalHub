package com.gernalix.personalhub.soldi

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gernalix.personalhub.core.alerts.HubAlarmPlatform
import com.gernalix.personalhub.core.alerts.HubAlarmPrecision
import com.gernalix.personalhub.core.alerts.HubNotificationPlatform
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal object FinanceReminderScheduler {
    private const val CHANNEL_ID = "soldi_reminders"
    private const val ACTION = "com.gernalix.personalhub.soldi.REMINDER"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_KEY = "key"
    private const val PREFS = "soldi_alarm_keys"
    private const val PREF_KEYS = "keys"

    suspend fun reschedule(context: Context) = withContext(Dispatchers.IO) {
        ensureChannel(context)
        cancelPreviouslyScheduled(context)
        val db = PersonalHubDatabase.get(context)
        val capsule = FinanceCapsule(db)
        val now = Instant.now()
        val scheduled = linkedSetOf<String>()

        db.financeDao().allTransactions().filter { it.reminderAt != null }.forEach { row ->
            val whenAt = row.reminderAt?.let(Instant::ofEpochMilli) ?: return@forEach
            if (whenAt.isAfter(now)) {
                val key = "transaction:${row.uuid}"
                schedule(context, key, titleFor(row.titleId, row.productId, db), whenAt)
                scheduled += key
            }
        }

        val today = LocalDate.now()
        val horizon = today.plusYears(1)
        db.financeDao().enabledRecurrences().forEach { rule ->
            val days = rule.reminderDaysBefore ?: return@forEach
            FinanceCapsule.occurrenceDates(rule, today, horizon).forEach { occurrence ->
                val remindAt = occurrence.minusDays(days.toLong()).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant()
                if (remindAt.isAfter(now)) {
                    val key = "recurrence:${rule.id}:${occurrence}"
                    schedule(context, key, rule.title, remindAt)
                    scheduled += key
                }
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(PREF_KEYS, scheduled).apply()
        Unit
    }

    private suspend fun titleFor(titleId: Long?, productId: Long?, db: PersonalHubDatabase): String =
        productId?.let { db.financeDao().product(it)?.name }
            ?: titleId?.let { db.financeDao().titleName(it) }
            ?: "Soldi"

    private fun cancelPreviouslyScheduled(context: Context) {
        val keys = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(PREF_KEYS, emptySet()).orEmpty()
        keys.forEach { key ->
            HubAlarmPlatform.cancel(
                context = context,
                operation = reminderIntent(context, key, ""),
                cancelPendingIntent = false,
            )
        }
    }

    private fun schedule(context: Context, key: String, title: String, at: Instant) {
        HubAlarmPlatform.schedule(
            context = context,
            triggerAtMs = at.toEpochMilli(),
            operation = reminderIntent(context, key, title),
            precision = HubAlarmPrecision.ALLOW_WHILE_IDLE,
        )
    }

    private fun reminderIntent(context: Context, key: String, title: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            Intent(context, FinanceReminderReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_TITLE, title),
            HubNotificationPlatform.pendingIntentFlags(),
        )

    fun ensureChannel(context: Context) {
        HubNotificationPlatform.ensureChannel(
            context = context,
            id = CHANNEL_ID,
            name = "Soldi reminders",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            description = "Upcoming finance transactions and recurring payments",
        )
    }

    fun post(context: Context, title: String, key: String) {
        ensureChannel(context)
        if (!HubNotificationPlatform.canPost(context)) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val content = launch?.let {
            PendingIntent.getActivity(
                context,
                key.hashCode(),
                it,
                HubNotificationPlatform.pendingIntentFlags(),
            )
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("Promemoria Soldi")
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        HubNotificationPlatform.post(context, key.hashCode(), notification)
    }
}

class FinanceReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FinanceReminderScheduler.post(
            context,
            intent.getStringExtra("title").orEmpty().ifBlank { "Soldi" },
            intent.getStringExtra("key").orEmpty().ifBlank { "soldi" },
        )
    }
}

class FinanceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                FinanceReminderScheduler.reschedule(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
