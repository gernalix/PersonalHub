package com.gernalix.personalhub.soldi

import android.content.Context
import com.gernalix.personalhub.core.database.DatabaseGate
import com.gernalix.personalhub.core.database.DatabasePreferences
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

internal data class GmailCatalog(
    val account: String? = null,
    val history: String? = null,
    val receipts: List<GmailReceipt> = emptyList(),
    val links: Map<String, String> = emptyMap(),
    val excluded: Set<String> = emptySet(),
    val automatic: Boolean = false,
    val days: Int = 90,
    val updatedAt: Instant? = null,
    val appointments: List<GmailAppointment> = emptyList(),
    val status: String = "idle",
)

/** Bounded metadata aggregate in personalhub.db; bodies, attachments and OAuth tokens are never persisted. */
internal class GmailReceiptStore(context: Context) {
    private val prefs = DatabasePreferences(context.applicationContext, "soldi.gmail.v1")
    fun read(): GmailCatalog {
        val raw = prefs.getString("catalog", null) ?: return GmailCatalog()
        val root = JSONObject(raw)
        val rows = root.getJSONArray("receipts")
        val links = root.getJSONObject("links")
        val excluded = root.getJSONArray("excluded")
        return GmailCatalog(root.gmailText("account"), root.gmailText("history"), (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            GmailReceipt(row.getString("id"), row.getString("subject"), row.getString("sender"), Instant.parse(row.getString("received")),
                row.gmailText("merchant"), row.gmailText("order"), row.gmailText("date")?.let(LocalDate::parse), row.gmailText("amount")?.let(::BigDecimal),
                row.gmailText("currency"), row.getString("parser"), row.optBoolean("available", true), row.optString("kind", "receipt"), row.gmailText("tracking"))
        }, links.keys().asSequence().associateWith { links.getString(it) }, (0 until excluded.length()).map { excluded.getString(it) }.toSet(),
            root.optBoolean("automatic"), root.optInt("days", 90), root.gmailText("updated")?.let(Instant::parse),
            root.optJSONArray("appointments")?.let { events -> (0 until events.length()).map { i ->
                val e = events.getJSONObject(i)
                GmailAppointment(e.getString("uid"), e.getString("message"), e.getString("title"), Instant.parse(e.getString("start")),
                    e.getString("location"), e.getInt("sequence"), e.getBoolean("cancelled"), e.getBoolean("allDay"), e.gmailText("dateOnly")?.let(LocalDate::parse))
            } }.orEmpty(), root.optString("status", "idle"))
    }
    fun update(change: (GmailCatalog) -> GmailCatalog): GmailCatalog = DatabaseGate.access {
        val next = change(read())
        require(next.receipts.size <= 5000 && next.appointments.size <= 5000 && next.days in 1..365)
        val json = JSONObject().apply {
            put("status", next.status); put("account", next.account); put("history", next.history); put("automatic", next.automatic); put("days", next.days)
            put("updated", next.updatedAt?.toString()); put("links", JSONObject(next.links)); put("excluded", JSONArray(next.excluded.toList()))
            put("appointments", JSONArray(next.appointments.map { e -> JSONObject().apply {
                put("uid", e.uid); put("message", e.messageId); put("title", e.title); put("start", e.startsAt.toString()); put("location", e.location)
                put("dateOnly", e.dateOnly?.toString()); put("sequence", e.sequence); put("cancelled", e.cancelled); put("allDay", e.allDay)
            } }))
            put("receipts", JSONArray(next.receipts.map { receipt -> JSONObject().apply {
                put("id", receipt.messageId); put("subject", receipt.subject); put("sender", receipt.sender); put("received", receipt.receivedAt.toString())
                put("merchant", receipt.merchant); put("order", receipt.orderId); put("date", receipt.date?.toString()); put("amount", receipt.amount?.toPlainString())
                put("kind", receipt.kind); put("tracking", receipt.tracking); put("currency", receipt.currency); put("parser", receipt.parser); put("available", receipt.available)
            } }))
        }.toString()
        require(json.toByteArray().size <= 8 * 1024 * 1024)
        check(prefs.edit().putString("catalog", json).commit())
        next
    }
    fun review(receipt: GmailReceipt) = update { current ->
        require(receipt.messageId !in current.links) { "Rimuovi il collegamento prima di correggere la ricevuta" }
        val previous = current.receipts.single { it.messageId == receipt.messageId }
        require(previous.subject == receipt.subject && previous.receivedAt == receipt.receivedAt && previous.available == receipt.available)
        current.copy(receipts = current.receipts.map { if (it.messageId == receipt.messageId) receipt else it })
    }
    fun link(match: ReceiptBankMatch, snapshot: () -> BankingSnapshot?) = update { current ->
        require(match in receiptBankCandidates(current.receipts.filterNot { it.messageId in current.excluded }, snapshot(), current.links, uniqueOnly = false)) {
            "Ricevuta o movimento modificato: ripeti il confronto"
        }
        current.copy(links = current.links + (match.messageId to match.bankKey))
    }
}
