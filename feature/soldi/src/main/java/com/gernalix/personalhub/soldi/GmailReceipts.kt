package com.gernalix.personalhub.soldi

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

internal data class GmailReceipt(
    val messageId: String,
    val subject: String,
    val sender: String,
    val receivedAt: Instant,
    val merchant: String?,
    val orderId: String?,
    val date: LocalDate?,
    val amount: BigDecimal?,
    val currency: String?,
    val parser: String,
    val available: Boolean = true,
    val kind: String = "receipt",
    val tracking: String? = null,
) {
    val complete get() = merchant != null && date != null && amount != null && currency != null
}

/** Parse only explicit fields. Missing/contradictory totals and dates require review. */
internal object GmailReceiptParser {
    fun parse(message: JSONObject): GmailReceipt? {
        val payload = message.getJSONObject("payload")
        val headers = payload.optJSONArray("headers") ?: JSONArray()
        fun header(name: String) = (0 until headers.length()).map { headers.getJSONObject(it) }
            .firstOrNull { it.optString("name").equals(name, true) }?.optString("value").orEmpty().take(500)
        val subject = header("Subject")
        val sender = header("From")
        val bodies = mutableListOf<Pair<String, String>>()
        fun visit(part: JSONObject, depth: Int) {
            require(depth <= 12)
            val mime = part.optString("mimeType")
            val data = part.optJSONObject("body")?.text("data")
            if (data != null && mime in setOf("text/plain", "text/html")) {
                require(data.length <= 2 * 1024 * 1024)
                bodies += mime to Base64.getUrlDecoder().decode(data).toString(Charsets.UTF_8)
            }
            val parts = part.optJSONArray("parts") ?: return
            require(parts.length() <= 100)
            for (i in 0 until parts.length()) visit(parts.getJSONObject(i), depth + 1)
        }
        visit(payload, 0)
        val html = bodies.filter { it.first == "text/html" }.joinToString("\n") { it.second }
        val plain = bodies.filter { it.first == "text/plain" }.joinToString("\n") { it.second }
            .ifBlank { html.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<br\\s*/?>|</(?:p|div|tr)>"), "\n").replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ").replace("&amp;", "&") }
        val structured = Regex("(?is)<script\\b[^>]*type\\s*=\\s*['\"]application/ld\\+json['\"][^>]*>(.*?)</script>")
            .findAll(html).mapNotNull { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }
            .filter { it.optString("@type") in setOf("Order", "Invoice", "ParcelDelivery") }.toList()
        val source = structured.singleOrNull()
        val kind = when {
            source?.optString("@type") in setOf("Order", "Invoice") -> "receipt"
            source?.optString("@type") == "ParcelDelivery" || Regex("(?i)shipment|shipped|spedizione|tracking|consegna").containsMatchIn(subject) -> "shipment"
            Regex("(?i)receipt|invoice|ricevuta|fattura|purchase|pagamento").containsMatchIn(subject) -> "receipt"
            Regex("(?i)appointment|appuntamento|invitation|invito|booking|prenotazione").containsMatchIn(subject) -> "appointment"
            else -> "receipt"
        }
        if (source == null && !Regex("(?i)receipt|invoice|order|ricevuta|fattura|ordine|kvittering|betaling|purchase|pagamento|shipment|shipped|spedizione|tracking|consegna|appointment|appuntamento|invitation|invito|booking|prenotazione").containsMatchIn(subject)) return null
        val totals = Regex("(?im)^\\s*(?:total(?:e)?(?: paid)?|amount paid|importo(?: totale)?|i alt)\\s*[:：]?\\s*(?:([A-Z]{3})\\s*)?([0-9]+(?:[.,][0-9]{2})?)\\s*([A-Z]{3})?\\s*$")
            .findAll(plain).mapNotNull { m ->
                val currency = (m.groupValues[1].ifBlank { m.groupValues[3] }).takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return@mapNotNull null
                BigDecimal(m.groupValues[2].replace(',', '.')) to currency
            }.distinct().toList()
        val payment = source?.optJSONObject("totalPaymentDue")
        val structuredAmount = source?.text("price") ?: payment?.text("price") ?: source?.text("totalPaymentDue")
        val amount = if (source != null) structuredAmount?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }
            else totals.singleOrNull()?.first?.takeIf { it.signum() > 0 }
        val currency = if (source != null) (source.text("priceCurrency") ?: payment?.text("priceCurrency"))?.takeIf { it.matches(Regex("[A-Z]{3}")) } else totals.singleOrNull()?.second
        val dates = Regex("(?im)^\\s*(?:date|data|order date|invoice date|dato)\\s*:\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$")
            .findAll(plain).mapNotNull { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }.distinct().toList()
        val date = if (source != null) (source.text("orderDate") ?: source.text("dateIssued"))?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            else dates.singleOrNull()
        val merchant = source?.optJSONObject("merchant")?.text("name") ?: source?.optJSONObject("broker")?.text("name")
            ?: Regex("(?im)^\\s*(?:merchant|venditore|negozio|seller)\\s*:\\s*(.{1,200})$").find(plain)?.groupValues?.get(1)?.trim()
        val order = source?.text("orderNumber") ?: source?.text("confirmationNumber")
            ?: Regex("(?im)^\\s*(?:order|ordine|invoice)(?: (?:id|number|numero))?\\s*[:#]\\s*([A-Za-z0-9._-]{1,100})\\s*$").find(plain)?.groupValues?.get(1)
        val tracking = source?.text("trackingNumber") ?: Regex("(?im)^\\s*(?:tracking(?: number| code)?|numero tracciamento)\\s*[:#]\\s*([A-Za-z0-9-]{6,60})\\s*$").find(plain)?.groupValues?.get(1)
        return GmailReceipt(message.getString("id"), subject, sender, Instant.ofEpochMilli(message.getString("internalDate").toLong()),
            merchant, order, date, amount, currency, if (source != null) "schema.org" else "campi espliciti", kind = kind, tracking = tracking)
    }
}

internal data class ReceiptBankMatch(val messageId: String, val bankKey: String)
internal fun receiptBankCandidates(receipts: List<GmailReceipt>, snapshot: BankingSnapshot?, linked: Map<String, String>, now: Instant = Instant.now(), uniqueOnly: Boolean = true): List<ReceiptBankMatch> {
    if (snapshot == null || !snapshot.cache.complete || snapshot.cache.fetchedAt == null || bankingCacheIsStale(snapshot.cache, now)) return emptyList()
    val used = linked.values.toSet()
    val candidates = receipts.filter { it.complete && it.available && it.messageId !in linked }.flatMap { receipt ->
        snapshot.transactions.distinctBy { it.accountId to it.id }.filter { bank ->
            val key = "${bank.accountId}:${bank.id}"
            bank.status == "BOOK" && bank.direction == "DBIT" && bank.accountId !in snapshot.partialAccounts && key !in used &&
                bank.currency == receipt.currency && bank.amount.negate().compareTo(receipt.amount) == 0 && bank.date != null &&
                kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(receipt.date, bank.date)) <= 3 &&
                bank.description?.let { description ->
                    val tokens = receipt.merchant!!.lowercase().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length >= 3 }
                    tokens.isNotEmpty() && tokens.all { it in description.lowercase() }
                } == true
        }.map { ReceiptBankMatch(receipt.messageId, "${it.accountId}:${it.id}") }
    }
    // Ambiguity in either direction stays for manual review; never pick the first equal amount.
    return if (!uniqueOnly) candidates else candidates.filter { row -> candidates.count { it.messageId == row.messageId } == 1 && candidates.count { it.bankKey == row.bankKey } == 1 }
}
