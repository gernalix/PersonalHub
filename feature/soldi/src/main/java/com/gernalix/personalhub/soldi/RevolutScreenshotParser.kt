package com.gernalix.personalhub.soldi

import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import java.math.BigDecimal

/** Parser for the visible text of Revolut transfer/exchange screenshots. OCR stays fully on-device. */
internal object RevolutScreenshotParser {
    data class Candidate(
        val sourceAmount: String,
        val sourceCurrency: String,
        val targetAmount: String,
        val targetCurrency: String,
        val quotedRate: String?,
        val feeAmount: String?,
        val feeCurrency: String?,
        val sourceAccountId: String?,
        val targetAccountId: String?,
    )

    fun parse(text: String, accounts: List<FinanceAccount>): Candidate? {
        val normalized = text.replace('−', '-').replace('–', '-').replace(" ", " ")
        val signed = signedAmounts(normalized)
        val negative = signed.firstOrNull { it.sign == '-' }
        val positive = signed.firstOrNull { it.sign == '+' }
        if (negative == null || positive == null) return null

        val sourceCurrency = resolveCurrency(negative.marker, accounts)
        val targetCurrency = resolveCurrency(positive.marker, accounts, exclude = sourceCurrency)
        val quote = parseQuote(normalized, sourceCurrency, targetCurrency)
        val fee = parseFee(normalized, accounts)
        val sourceAccount = matchRevolutAccount(accounts, sourceCurrency)
        val targetAccount = matchRevolutAccount(accounts, targetCurrency, sourceAccount?.id)

        return Candidate(
            sourceAmount = negative.amount,
            sourceCurrency = sourceCurrency,
            targetAmount = positive.amount,
            targetCurrency = targetCurrency,
            quotedRate = quote,
            feeAmount = fee?.second,
            feeCurrency = fee?.first,
            sourceAccountId = sourceAccount?.id,
            targetAccountId = targetAccount?.id,
        )
    }

    private data class SignedAmount(val sign: Char, val marker: String, val amount: String)

    private fun signedAmounts(text: String): List<SignedAmount> {
        val patterns = listOf(
            Regex("([+-])\\s*([€$£])\\s*([0-9][0-9.,]*)"),
            Regex("([+-])\\s*(kr\\.?|DKK|EUR|USD|GBP)\\s*([0-9][0-9.,]*)", RegexOption.IGNORE_CASE),
            Regex("([+-])\\s*([0-9][0-9.,]*)\\s*(DKK|EUR|USD|GBP)", RegexOption.IGNORE_CASE),
        )
        return patterns.flatMap { regex ->
            regex.findAll(text).mapNotNull { match ->
                val groups = match.groupValues.drop(1)
                val sign = groups.firstOrNull()?.firstOrNull() ?: return@mapNotNull null
                val marker: String
                val raw: String
                if (groups[1].firstOrNull()?.isDigit() == true) {
                    raw = groups[1]
                    marker = groups[2]
                } else {
                    marker = groups[1]
                    raw = groups[2]
                }
                parseNumber(raw)?.let { SignedAmount(sign, marker, it) }
            }.toList()
        }.distinctBy { Triple(it.sign, it.marker, it.amount) }
    }

    private fun parseQuote(text: String, source: String, target: String): String? {
        val symbol = Regex("(?:€|EUR)\\s*1\\s*=\\s*(?:kr\\.?|DKK)\\s*([0-9][0-9.,]*)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.let(::parseNumber)
        if (symbol != null && source == "EUR" && target == "DKK") return symbol
        val iso = Regex("1\\s*([A-Z]{3})\\s*=\\s*([0-9][0-9.,]*)\\s*([A-Z]{3})", RegexOption.IGNORE_CASE).find(text)
        if (iso != null && iso.groupValues[1].uppercase() == source && iso.groupValues[3].uppercase() == target) {
            return parseNumber(iso.groupValues[2])
        }
        return null
    }

    private fun parseFee(text: String, accounts: List<FinanceAccount>): Pair<String, String>? {
        val prefix = Regex("(?:after\\s+)?(€|\\$|£|kr\\.?|DKK|EUR|USD|GBP)\\s*([0-9][0-9.,]*)\\s*fee", RegexOption.IGNORE_CASE).find(text)
        if (prefix != null) {
            val amount = parseNumber(prefix.groupValues[2]) ?: return null
            return resolveCurrency(prefix.groupValues[1], accounts) to amount
        }
        val suffix = Regex("fee\\s*(€|\\$|£|kr\\.?|DKK|EUR|USD|GBP)\\s*([0-9][0-9.,]*)", RegexOption.IGNORE_CASE).find(text)
        if (suffix != null) {
            val amount = parseNumber(suffix.groupValues[2]) ?: return null
            return resolveCurrency(suffix.groupValues[1], accounts) to amount
        }
        return null
    }

    private fun resolveCurrency(marker: String, accounts: List<FinanceAccount>, exclude: String? = null): String = when (marker.trim().uppercase()) {
        "€", "EUR" -> "EUR"
        "$", "USD" -> "USD"
        "£", "GBP" -> "GBP"
        "DKK" -> "DKK"
        "KR", "KR." -> accounts
            .filter { it.currency != exclude && it.name.contains("revolut", true) && it.currency in setOf("DKK", "SEK", "NOK") }
            .map { it.currency }.distinct().singleOrNull()
            ?: accounts.filter { it.currency != exclude && it.currency in setOf("DKK", "SEK", "NOK") }.map { it.currency }.distinct().singleOrNull()
            ?: "DKK"
        else -> marker.trim().uppercase()
    }

    private fun matchRevolutAccount(accounts: List<FinanceAccount>, currency: String, excludeId: String? = null): FinanceAccount? =
        accounts.firstOrNull { it.id != excludeId && it.currency == currency && it.name.contains("revolut", true) }
            ?: accounts.firstOrNull { it.id != excludeId && it.currency == currency }

    private fun parseNumber(raw: String): String? {
        val cleaned = raw.trim().replace(" ", "")
        if (cleaned.isBlank()) return null
        val comma = cleaned.lastIndexOf(',')
        val dot = cleaned.lastIndexOf('.')
        val normalized = when {
            comma >= 0 && dot >= 0 -> if (dot > comma) cleaned.replace(",", "") else cleaned.replace(".", "").replace(',', '.')
            comma >= 0 -> {
                val decimals = cleaned.length - comma - 1
                if (decimals in 1..2) cleaned.replace(',', '.') else cleaned.replace(",", "")
            }
            else -> cleaned
        }
        return runCatching { BigDecimal(normalized).stripTrailingZeros().toPlainString() }.getOrNull()
    }
}
