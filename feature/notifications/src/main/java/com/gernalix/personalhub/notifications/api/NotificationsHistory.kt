package com.gernalix.personalhub.notifications.api

import android.content.Context
import com.gernalix.personalhub.notifications.capsules.archive.ArchiveStore
import java.time.Instant

data class NotificationHistoryRow(
    val eventId: Long,
    val occurredAt: Long,
    val kind: String,
    val appLabel: String,
    val title: String,
    val body: String?,
) {
    val key: String get() = "notifications:" + eventId.toString().padStart(20, '0')
}

/** A bounded read of the original private archive. Never copies events into Room or Git. */
object NotificationsHistory {
    fun query(context: Context, search: String, fromMs: Long?, toMs: Long?,
              beforeAt: Long?, beforeKey: String?, limit: Int): List<NotificationHistoryRow> {
        require(limit in 1..101 && (beforeAt == null) == (beforeKey == null))
        val labels = mutableMapOf<String, String>()
        return ArchiveStore.get(context).historyEvents(search, fromMs, toMs, beforeAt, beforeKey, limit).map { event ->
            val snapshot = event.snapshot
            val packageName = snapshot.getString("package")
            val appLabel = labels.getOrPut(packageName) {
                runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }
                    .getOrDefault(packageName)
            }
            fun text(key: String) = snapshot.optString(key).takeUnless { it.isBlank() || it == "null" }
            NotificationHistoryRow(event.id, Instant.parse(snapshot.getString("captured")).toEpochMilli(), event.kind,
                appLabel, text("title") ?: appLabel, (text("bigText") ?: text("text"))?.take(2000))
        }
    }
}
