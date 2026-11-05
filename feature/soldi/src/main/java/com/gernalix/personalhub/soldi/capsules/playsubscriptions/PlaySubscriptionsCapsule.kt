package com.gernalix.personalhub.soldi.capsules.playsubscriptions

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.work.ListenableWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Internal Soldi entrypoint: persistence, parsing and sync remain owned by this capsule. */
internal object PlaySubscriptionsCapsule {
    @Composable
    fun Screen(onRequestNotifications: () -> Unit) = PlaySubscriptionsScreen(onRequestNotifications)

    data class Reminder(val key: String, val title: String, val at: Instant)
    fun reminders(context: Context): List<Reminder> {
        val catalog = PlaySubscriptionStore(context).read()
        if (!catalog.reminders) return emptyList()
        val today = LocalDate.now()
        val now = Instant.now()
        return catalog.forecastSubscriptions.flatMap { subscription ->
            subscription.renewalDates(today, today.plusYears(1)).mapNotNull { occurrence ->
                val at = occurrence.minusDays(catalog.reminderDays.toLong()).atTime(9, 0)
                    .atZone(ZoneId.systemDefault()).toInstant()
                if (at.isAfter(now)) Reminder("play:${subscription.key}:$occurrence", subscription.title, at) else null
            }
        }
    }
    fun restoreBackgroundWork(context: Context) = PlaySubscriptionWork.schedule(context, PlaySubscriptionStore(context).read())

    suspend fun runWorker(context: Context, attempt: Int): ListenableWorker.Result = runPlaySubscriptionWork(context, attempt)
}
