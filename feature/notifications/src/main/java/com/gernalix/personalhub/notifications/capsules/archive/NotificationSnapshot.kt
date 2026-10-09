package com.gernalix.personalhub.notifications.capsules.archive

import androidx.core.app.NotificationCompat
import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Only observed values. No parcelables, actions, RemoteViews, image bytes or foreign file reads. */
internal object NotificationSnapshot {
    fun capture(sbn: StatusBarNotification, captured: Long = System.currentTimeMillis(), conversation: String? = null): JSONObject {
        val n = sbn.notification
        val e = n.extras ?: Bundle.EMPTY
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        fun person(p: androidx.core.app.Person?): JSONObject? = p?.let {
            JSONObject().put("name", it.name?.toString()).put("key", it.key).put("uri", it.uri).put("bot", it.isBot)
        }
        fun messages(items: List<NotificationCompat.MessagingStyle.Message>): JSONArray = JSONArray().also { out ->
            items.forEach { m -> out.put(JSONObject().put("text", m.text?.toString())
                .put("timestamp", if (m.timestamp > 0) Instant.ofEpochMilli(m.timestamp).toString() else JSONObject.NULL)
                .put("sender", person(m.person)).put("mime", m.dataMimeType).put("dataUri", m.dataUri?.toString())) }
        }
        val extras = JSONObject()
        // Relevant scalar metadata only. Unserializable values are not silently stringified.
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_CONVERSATION_TITLE,
            Notification.EXTRA_IS_GROUP_CONVERSATION, Notification.EXTRA_TEMPLATE).forEach { key ->
            when (val value = e.get(key)) {
                is CharSequence -> extras.put(key, value.toString())
                is Boolean, is Number -> extras.put(key, value)
            }
        }
        return JSONObject().put("package", sbn.packageName).put("key", sbn.key).put("id", sbn.id)
            .put("user", sbn.userId).put("tag", sbn.tag).put("channel", n.channelId)
            .put("shortcut", n.shortcutId).put("conversation", conversation).put("group", sbn.groupKey)
            .put("summary", n.flags and Notification.FLAG_GROUP_SUMMARY != 0).put("category", n.category)
            .put("posted", Instant.ofEpochMilli(sbn.postTime).toString())
            .put("when", if (n.`when` > 0) Instant.ofEpochMilli(n.`when`).toString() else JSONObject.NULL)
            .put("captured", Instant.ofEpochMilli(captured).toString())
            .put("title", e.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            .put("text", e.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .put("bigText", e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            .put("lines", JSONArray(e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList<String>()))
            .put("extras", extras).put("messaging", style != null).put("self", person(style?.user))
            .put("conversationTitle", style?.conversationTitle?.toString()).put("isGroup", style?.isGroupConversation)
            .put("messages", messages(style?.messages ?: emptyList()))
            .put("historicMessages", messages(style?.historicMessages ?: emptyList()))
    }
}
