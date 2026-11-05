package com.gernalix.personalhub.soldi

import android.content.Context
import com.gernalix.personalhub.core.database.DatabaseGate
import com.gernalix.personalhub.core.database.DatabasePreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One bounded aggregate in the existing SQLite namespace store; included in atomic DB exports. No secrets. */
internal class PlaySubscriptionStore(context: Context) {
    private val prefs = DatabasePreferences(context.applicationContext, "soldi.play_subscriptions.v1")
    fun read(): PlayCatalog = decode(prefs.getString("catalog", null))
    fun update(change: (PlayCatalog) -> PlayCatalog): PlayCatalog = DatabaseGate.access {
        val next = change(read())
        check(prefs.edit().putString("catalog", encode(next)).commit())
        next
    }
    fun import(rows: List<PlaySubscription>, now: Instant = Instant.now(), fullSnapshot: Boolean = false): PlayCatalog = update { previous ->
        val merged = (previous.subscriptions.associateBy { it.key } + PlaySubscriptionJson.unique(rows).associateBy { it.key }).values.sortedBy { it.title }
        require(merged.size <= 10000) { "Troppe sottoscrizioni" }
        previous.copy(subscriptions = merged, importedAt = now,
            unconfirmedKeys = if (fullSnapshot) merged.map { it.key }.toSet() - rows.map { it.key }.toSet()
                else (previous.unconfirmedKeys + previous.subscriptions.filter { sub ->
                    rows.any { it.key.startsWith("snapshot:") } && sub.key.startsWith("snapshot:")
                }.map { it.key }) - rows.map { it.key }.toSet())
    }
    fun link(match: PlayBankMatch) = update { previous ->
        val subscription = previous.subscriptions.single { it.key == match.subscriptionKey }
        require(subscription.amount?.compareTo(match.amount) == 0 && subscription.currency == match.currency) { "Abbonamento modificato: ripeti il confronto" }
        require(match.bankKey !in previous.links) { "Addebito già associato" }
        previous.copy(links = previous.links + (match.bankKey to match.subscriptionKey))
    }
    companion object {
        private fun encode(value: PlayCatalog): String = JSONObject().apply {
            put("subscriptions", JSONArray(value.subscriptions.map { JSONObject(it.sourceJson) }))
            put("unconfirmedKeys", JSONArray(value.unconfirmedKeys.toList()))
            put("links", JSONObject(value.links)); put("reminders", value.reminders)
            put("reminderDays", value.reminderDays); put("automatic", value.automatic)
            put("importedAt", value.importedAt?.toString()); put("initiatedAt", value.initiatedAt?.toString())
            put("job", value.job); put("accessType", value.accessType); put("status", value.status)
        }.toString().also { require(it.toByteArray().size <= PlaySubscriptionJson.MAX_BYTES) }
        private fun decode(text: String?): PlayCatalog {
            if (text == null) return PlayCatalog()
            val root = JSONObject(text)
            val links = root.getJSONObject("links")
            return PlayCatalog(
                subscriptions = PlaySubscriptionJson.parse(root.getJSONArray("subscriptions").toString()),
                unconfirmedKeys = root.optJSONArray("unconfirmedKeys")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
                links = links.keys().asSequence().associateWith { links.getString(it) },
                reminders = root.optBoolean("reminders"), reminderDays = root.optInt("reminderDays", 3),
                automatic = root.optBoolean("automatic"),
                importedAt = root.text("importedAt")?.let(Instant::parse),
                initiatedAt = root.text("initiatedAt")?.let(Instant::parse), job = root.text("job"),
                accessType = root.text("accessType"), status = root.optString("status", "idle"),
            )
        }
    }
}
internal data class PlayCatalog(
    val subscriptions: List<PlaySubscription> = emptyList(),
    val links: Map<String, String> = emptyMap(),
    val unconfirmedKeys: Set<String> = emptySet(),
    val reminders: Boolean = false,
    val reminderDays: Int = 3,
    val automatic: Boolean = false,
    val importedAt: Instant? = null,
    val initiatedAt: Instant? = null,
    val job: String? = null,
    val accessType: String? = null,
    val status: String = "idle",
) {
    val forecastSubscriptions get() = subscriptions.filterNot { it.key in unconfirmedKeys }
}
