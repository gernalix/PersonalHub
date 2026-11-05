package com.gernalix.personalhub.soldi

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.ZipInputStream

/** A source record is a forecast, never a posting finance transaction. Unknown values stay unknown. */
internal data class PlaySubscription(
    val key: String,
    val title: String,
    val packageName: String?,
    val state: String,
    val renewal: Instant?,
    val expiration: Instant?,
    val amount: BigDecimal?,
    val currency: String?,
    val period: Period?,
    val sourceJson: String,
) {
    val renewing: Boolean get() = state.uppercase() in setOf("ACTIVE", "SUBSCRIPTION_STATE_ACTIVE") && renewal != null
    fun annualCost(): BigDecimal? {
        if (!renewing) return null
        val p = period ?: return null
        val price = amount ?: return null
        val months = p.toTotalMonths()
        return when {
            months > 0 && p.days == 0 -> price.multiply(BigDecimal(12)).divide(BigDecimal(months), 8, RoundingMode.HALF_UP)
            months == 0L && p.days > 0 -> price.multiply(BigDecimal(365)).divide(BigDecimal(p.days), 8, RoundingMode.HALF_UP)
            else -> null
        }
    }
    fun renewalDates(from: LocalDate, until: LocalDate): List<LocalDate> {
        if (!renewing) return emptyList()
        val first = renewal!!.atZone(ZoneId.systemDefault()).toLocalDate()
        val p = period
        if (p == null) return listOf(first).filter { it >= from && it <= until }
        return buildList {
            // Derive each date from the original anchor (Jan 31 -> Feb 28 -> Mar 31).
            for (i in 0..2400) {
                val day = first.plus(p.multipliedBy(i))
                if (day > until || expiration?.atZone(ZoneId.systemDefault())?.toLocalDate()?.let { day > it } == true) break
                if (day >= from) add(day)
            }
        }
    }
}

internal object PlaySubscriptionJson {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun parse(text: String): List<PlaySubscription> {
        require(text.toByteArray().size <= MAX_BYTES) { "Esportazione troppo grande" }
        val root = JSONTokener(text).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("subscriptions") ?: root.optJSONArray("Subscriptions")
                ?: if (root.has("doc")) JSONArray().put(root) else error("Il file non contiene sottoscrizioni Google Play")
            else -> error("Formato Google Play non riconosciuto")
        }
        require(array.length() <= 10000) { "Troppe sottoscrizioni" }
        return unique((0 until array.length()).map { parseRecord(array.getJSONObject(it)) })
    }
    private fun parseRecord(row: JSONObject): PlaySubscription {
        val doc = row.getJSONObject("doc")
        val title = doc.text("title") ?: error("Nome della sottoscrizione mancante")
        val packageName = doc.text("packageName") ?: doc.optJSONObject("documentId")?.text("packageName")
        val documentId = doc.text("docid") ?: doc.text("docId") ?: doc.text("id")
        val identity = row.text("subscriptionId") ?: documentId ?: packageName
            ?: error("Identità stabile della sottoscrizione mancante")
        val pricing = row.optJSONObject("pricing")
        val price = pricing?.optJSONObject("price")
        val currency = (price?.text("currencyCode") ?: pricing?.text("currencyCode"))
            ?.takeIf { Regex("[A-Z]{3}").matches(it) }
        val amount = when {
            price?.has("units") == true -> BigDecimal(price.getString("units")) + BigDecimal(price.optLong("nanos", 0)).movePointLeft(9)
            pricing?.has("priceMicros") == true -> BigDecimal(pricing.getString("priceMicros")).movePointLeft(6)
            else -> (pricing?.text("amount") ?: pricing?.text("price"))?.toBigDecimalOrNull()
        }?.also { require(it.signum() >= 0 && it.precision() <= 30 && it.scale() <= 12) { "Prezzo non valido" } }
        val period = (pricing?.text("period") ?: pricing?.text("billingPeriod"))?.let {
            runCatching { Period.parse(it) }.getOrNull()?.takeIf { p -> !p.isZero && !p.isNegative && p.years <= 20 && p.months <= 240 && p.days <= 7300 }
        }
        fun date(key: String) = row.text(key)?.let { value ->
            runCatching { Instant.parse(value) }.getOrElse { error("Data Google Play non valida") }
        }
        return PlaySubscription(hash(identity), title, packageName?.takeIf { Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it) },
            row.text("state") ?: "UNKNOWN", date("renewalDate"), date("expirationDate"), amount, currency, period, row.toString())
    }
    fun read(input: InputStream): List<PlaySubscription> {
        val buffered = input.buffered()
        buffered.mark(4)
        val first = buffered.read(); val second = buffered.read(); buffered.reset()
        if (first != 80 || second != 75) return parse(bounded(buffered, MAX_BYTES).toString(Charsets.UTF_8))
        var budget = MAX_BYTES
        var entries = 0
        val records = mutableListOf<PlaySubscription>()
        var found = false
        ZipInputStream(buffered).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 2000) { "Archivio con troppi file" }
                // Stream and bound *all* decompressed entries; no paths are extracted to disk.
                val bytes = bounded(zip, budget); budget -= bytes.size
                if (!entry.isDirectory && entry.name.substringAfterLast('/').contains("subscription", true) && entry.name.endsWith(".json", true)) {
                    found = true
                    records += parse(bytes.toString(Charsets.UTF_8))
                }
            }
        }
        require(found) { "Nessun file di sottoscrizioni Google Play nell'archivio" }
        return unique(records)
    }
    fun unique(records: List<PlaySubscription>): List<PlaySubscription> = records.groupBy { it.key }.values.map { rows ->
        require(rows.distinct().size == 1) { "Identità Google Play ambigua: archivio non importato" }
        rows.first()
    }
    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= limit) { "Esportazione troppo grande" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun JSONObject.text(key: String): String? = opt(key).takeUnless { it == null || it == JSONObject.NULL || it is JSONObject || it is JSONArray }?.toString()?.takeIf { it.isNotBlank() }

internal data class PlayBankMatch(val subscriptionKey: String, val bankKey: String, val title: String, val date: LocalDate, val amount: BigDecimal, val currency: String)

/** Conservative suggestions require booked debits, exact currency/amount, title, date and a unique candidate. */
internal fun playBankMatches(subscriptions: List<PlaySubscription>, snapshot: BankingSnapshot?, linked: Set<String>): List<PlayBankMatch> {
    if (snapshot == null || bankingCacheIsStale(snapshot.cache) || !snapshot.cache.complete || snapshot.partialAccounts.isNotEmpty()) return emptyList()
    val candidates = subscriptions.flatMap { sub ->
        deduplicateBankingTransactions(snapshot.transactions).mapNotNull { tx ->
            val date = tx.date ?: return@mapNotNull null
            val bankKey = "${tx.accountId}:${tx.id}"
            val description = tx.description?.lowercase() ?: return@mapNotNull null
            val title = sub.title.lowercase().trim()
            if (bankKey in linked || tx.status != "BOOK" || tx.direction != "DBIT" || tx.amount.signum() >= 0 ||
                tx.currency != sub.currency || sub.amount?.compareTo(tx.amount.abs()) != 0 || title.length < 3 || !description.contains(title)) return@mapNotNull null
            val anchor = sub.renewal?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: return@mapNotNull null
            val dates = mutableListOf(anchor)
            sub.period?.let { period -> (1..120).forEach { dates += anchor.minus(period.multipliedBy(it)) } }
            if (dates.none { kotlin.math.abs(ChronoUnit.DAYS.between(it, date)) <= 3 }) return@mapNotNull null
            PlayBankMatch(sub.key, bankKey, sub.title, date, tx.amount.abs(), tx.currency)
        }
    }
    return candidates.filter { row -> candidates.count { it.bankKey == row.bankKey } == 1 }
}
