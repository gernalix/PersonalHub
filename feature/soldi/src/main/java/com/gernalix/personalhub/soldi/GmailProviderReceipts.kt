package com.gernalix.personalhub.soldi

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Currency
import java.util.Locale

internal data class GmailProviderFields(
    val provider: String,
    val merchant: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val date: LocalDate?,
)

/** Recurring provider formats, including Google's non-ISO dates and localized subscription totals. */
internal object GmailProviderReceipts {
    fun parse(sender: String, body: String): GmailProviderFields? {
        val address = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@([A-Za-z0-9.-]+)").findAll(sender).toList().singleOrNull()?.value?.lowercase() ?: return null
        val domain = address.substringAfter('@')
        val lines = body.lines().map { it.replace('\u00a0', ' ').trim() }.filter(String::isNotBlank)
        val google = address == "googleplay-noreply@google.com"
        val paypal = domain in setOf("paypal.com", "paypal.it", "paypal.dk", "paypal.co.uk") || domain.endsWith(".paypal.com")
        if (!google && !paypal) return null
        val dateLabels = if (google) setOf("order date", "data ordine", "data dell'ordine", "ordredato")
            else setOf("transaction date", "date", "data", "data della transazione", "data transazione", "dato")
        val dates = labelled(lines, dateLabels).mapNotNull(::date).distinct()
        if (google) {
            val totals = labelled(lines, setOf("total", "totale", "i alt")).mapNotNull(::money).distinct()
            val total = totals.singleOrNull()
            return GmailProviderFields("Google Play", "Google Play", total?.first, total?.second, dates.singleOrNull())
        }
        // Authorization/automatic-payment agreements are not receipts for a completed payment.
        val sent = Regex("(?im)(?:You sent a payment of|You've sent a payment of|Hai inviato un pagamento di|Du har sendt en betaling på)\\s+([^\\r\\n]{1,350})")
            .find(body)?.groupValues?.get(1) ?: return null
        val separator = Regex("(?i)\\s+(?:to|a|til)\\s+").find(sent) ?: return null
        val total = money(sent.substring(0, separator.range.first)) ?: return null
        val merchant = sent.substring(separator.range.last + 1).substringBefore(" (").trim().takeIf { it.length in 1..200 }
        return GmailProviderFields("PayPal", merchant, total.first, total.second, dates.singleOrNull())
    }

    private fun labelled(lines: List<String>, labels: Set<String>): List<String> = lines.mapIndexedNotNull { i, line ->
        if (line.length > 500) return@mapIndexedNotNull null
        val normalized = line.lowercase()
        val label = labels.sortedByDescending(String::length).firstOrNull {
            normalized == it || normalized.startsWith("$it:") || normalized.startsWith("$it ")
        } ?: return@mapIndexedNotNull null
        line.substring(label.length).trim().removePrefix(":").trim().ifBlank { lines.getOrNull(i + 1).orEmpty() }
    }

    private fun money(text: String): Pair<BigDecimal, String?>? {
        if (text.length > 500) return null
        val match = Regex("(?<![\\d.,])\\d[\\d.,\\u00a0 ]*").find(text) ?: return null
        val prefix = text.substring(0, match.range.first).trim()
        if (prefix.any { it in "-−(" }) return null
        val currencyPrefix = prefix.filterNot { it in "€$£¥ " }.trim()
        if (currencyPrefix.isNotBlank() && currencyPrefix.lowercase() !in setOf("kr", "kr.") &&
            !(currencyPrefix.matches(Regex("[A-Z]{3}")) && runCatching { Currency.getInstance(currencyPrefix) }.isSuccess)) return null
        val raw = match.value.trim().replace('\u00a0', ' ')
        val decimal = when {
            raw.matches(Regex("\\d+(?:[.,]\\d{2})?")) -> raw.replace(',', '.')
            (raw.matches(Regex("\\d{1,3}(?:,\\d{3})+\\.\\d{2}")) || raw.matches(Regex("\\d{1,3}(?:\\.\\d{3})+,\\d{2}")) || raw.matches(Regex("\\d{1,3}(?: \\d{3})+[.,]\\d{2}"))) -> raw.dropLast(3).filter(Char::isDigit) + "." + raw.takeLast(2)
            else -> return null
        }
        val amount = decimal.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.precision() <= 18 } ?: return null
        val currencies = Regex("\\b[A-Z]{3}\\b").findAll(text).mapNotNull { runCatching { Currency.getInstance(it.value).currencyCode }.getOrNull() }.toMutableSet()
        if ('€' in text) currencies += "EUR"
        // kr, $, and periodic suffixes do not identify a currency; preserve the missing field for review.
        return amount to currencies.singleOrNull()
    }

    private val formats: List<DateTimeFormatter> = listOf(Locale.ENGLISH, Locale.ITALIAN, Locale.forLanguageTag("da-DK")).flatMap { locale ->
        listOf("MMM d, uuuu", "MMMM d, uuuu", "d MMM uuuu", "d MMMM uuuu", "d. MMM uuuu", "d. MMMM uuuu").map { pattern ->
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(locale).withResolverStyle(ResolverStyle.STRICT)
        }
    }
    private fun date(text: String): LocalDate? {
        val iso = Regex("^\\d{4}-\\d{2}-\\d{2}").find(text)?.value
        if (iso != null) return runCatching { LocalDate.parse(iso) }.getOrNull()
        val prefix = Regex("^(?:[\\p{L}.]+ \\d{1,2}, \\d{4}|\\d{1,2}\\.? [\\p{L}.]+ \\d{4})").find(text)?.value ?: return null
        return formats.firstNotNullOfOrNull { runCatching { LocalDate.parse(prefix, it) }.getOrNull() }
    }
}
