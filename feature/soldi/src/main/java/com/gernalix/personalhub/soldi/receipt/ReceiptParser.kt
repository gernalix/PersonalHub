package com.gernalix.personalhub.soldi.receipt

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

enum class ReceiptChain(val displayName: String) {
    LIDL("LIDL"),
    NETTO("Netto"),
    REMA_1000("REMA 1000"),
    UNKNOWN(""),
}

data class ParsedReceipt(
    val merchant: String,
    val dateTime: String?,
    val total: String?,
    val currency: String,
    val rawText: String,
    val lines: List<ParsedReceiptLine>,
    val warnings: List<String>,
    val chain: ReceiptChain,
)

data class ParsedReceiptLine(
    val description: String,
    val quantity: Double? = null,
    val unitPrice: String? = null,
    val totalPrice: String,
    val confidence: Float,
    val source: String,
)

/**
 * Local-first parser. It retains the former Soldi Lidl/Netto conventions and adds a generic
 * description/price parser so unknown shops remain usable without any network service.
 */
class ReceiptParser {
    private val totalMarker = Regex(
        "^(?:AT\\s+BETAL(?:E)?|I\\s*ALT(?:\\s+INKL\\.?\\s+MOMS)?|SUM|TOTAL)(?:\\b|\\s|:)",
        RegexOption.IGNORE_CASE,
    )
    private val paymentFooter = Regex(
        "^(?:VISA|MASTERCARD|MAESTRO|MOBILEPAY|KORT|CARDHOLDER|BETALING|PAYMENT|PURCHASE)(?:\\b|\\s|:)",
        RegexOption.IGNORE_CASE,
    )
    private val itemWithPrice = Regex(
        "^(.+?)\\s+(-?\\d{1,5}[,.]\\d{2})(?:\\s*(?:KR\\.?|DKK))?$",
        RegexOption.IGNORE_CASE,
    )
    private val priceOnly = Regex("^-?\\d{1,5}[,.]\\d{2}(?:\\s*(?:KR\\.?|DKK))?$", RegexOption.IGNORE_CASE)
    private val quantityPrefix = Regex("^(\\d+(?:[,.]\\d+)?)\\s*[xX]?\\s+(.+)$")
    private val unitPriceLine = Regex("^(?:à|á|a|@)\\s*-?\\d{1,5}[,.]\\d{2}(?:\\s*(?:kr\\.?|dkk))?$", RegexOption.IGNORE_CASE)
    private val metadata = Regex(
        "(?:^kvittering$|^receipt$|^dkk$|cvr|tlf|telefon|barcode|moms|vat|terminal|auth|ref:|aid|mid:|tid:|bank|kort|card|visa|mastercard|mobilepay|tak for|point|bonus|loyalty|medlem|kunde|www\\.|@)",
        RegexOption.IGNORE_CASE,
    )
    private val addressLike = Regex("\\b(?:gade|vej|all[eé]|plads|boulevard|street|road)\\b.*\\d", RegexOption.IGNORE_CASE)
    private val dateTimePatterns = listOf(
        Regex("\\b(\\d{2}\\.\\d{2}\\.\\d{2,4})\\s+(\\d{1,2}:\\d{2})\\b"),
        Regex("\\b(\\d{4}[-/]\\d{2}[-/]\\d{2})\\s+(\\d{1,2}:\\d{2})\\b"),
        Regex("\\b(\\d{1,2}\\s+[A-ZÆØÅa-zæøå]+\\s+\\d{4})\\b"),
    )

    fun parse(rawText: String): ParsedReceipt {
        val lines = normalize(rawText)
        val chain = detectChain(lines)
        val merchant = when (chain) {
            ReceiptChain.UNKNOWN -> lines.firstOrNull(::looksLikeMerchant).orEmpty()
            else -> chain.displayName
        }
        val total = findTotal(lines)
        val parsedLines = parseProductLines(lines, chain, total)
        val warnings = buildList {
            if (parsedLines.isEmpty()) add("no_product_lines")
            if (total == null) add("total_not_found")
            if (findDateTime(lines) == null) add("date_not_found")
            totalMismatch(total, parsedLines)?.let(::add)
            if (parsedLines.any { it.confidence < 0.70f }) add("review_low_confidence")
        }
        return ParsedReceipt(
            merchant = merchant,
            dateTime = findDateTime(lines),
            total = total,
            currency = if (lines.any { it.contains("EUR", true) }) "EUR" else "DKK",
            rawText = rawText,
            lines = parsedLines,
            warnings = warnings,
            chain = chain,
        )
    }

    private fun detectChain(lines: List<String>): ReceiptChain = when {
        lines.any { it.contains("LIDL", true) || it.equals("LGDL", true) } -> ReceiptChain.LIDL
        lines.any { it.contains("NETTO", true) } -> ReceiptChain.NETTO
        lines.any { it.contains("REMA 1000", true) || it.contains("REMA1000", true) } -> ReceiptChain.REMA_1000
        else -> ReceiptChain.UNKNOWN
    }

    private fun findTotal(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            if (!totalMarker.containsMatchIn(line)) return@forEachIndexed
            ReceiptMoney.normalize(line)?.let { return it }
            lines.drop(index + 1).take(3).firstNotNullOfOrNull(ReceiptMoney::normalize)?.let { return it }
        }
        val explicit = Regex("(?:AT\\s+BETAL(?:E)?|I\\s*ALT|SUM|TOTAL).*?(-?\\d{1,5}[,.]\\d{2})", RegexOption.IGNORE_CASE)
        return lines.firstNotNullOfOrNull { explicit.find(it)?.groupValues?.getOrNull(1)?.let(ReceiptMoney::normalize) }
    }

    private fun findDateTime(lines: List<String>): String? {
        for (line in lines) {
            for (regex in dateTimePatterns) {
                val match = regex.find(line) ?: continue
                return match.value.trim()
            }
        }
        return null
    }

    private fun parseProductLines(lines: List<String>, chain: ReceiptChain, receiptTotal: String?): List<ParsedReceiptLine> {
        val result = mutableListOf<ParsedReceiptLine>()
        var pending: PendingDescription? = null
        val receiptTotalMinor = ReceiptMoney.toMinorUnits(receiptTotal)

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (totalMarker.containsMatchIn(line) || paymentFooter.containsMatchIn(line)) break
            if (shouldIgnore(line, chain)) continue

            if (unitPriceLine.matches(line)) {
                pending = pending?.copy(unitPrice = ReceiptMoney.normalize(line))
                continue
            }

            itemWithPrice.matchEntire(line)?.let { match ->
                val parsedName = parseDescription(match.groupValues[1])
                val amount = ReceiptMoney.normalize(match.groupValues[2])
                if (parsedName.name.isMeaningful() && amount != null && !isReceiptTotal(amount, receiptTotalMinor)) {
                    result += ParsedReceiptLine(
                        description = parsedName.name,
                        quantity = parsedName.quantity,
                        totalPrice = amount,
                        confidence = if (chain == ReceiptChain.UNKNOWN) 0.78f else 0.92f,
                        source = "local-${chain.name.lowercase(Locale.ROOT)}",
                    )
                    pending = null
                    continue
                }
            }

            val standalone = ReceiptMoney.normalize(line)
            if (standalone != null && priceOnly.matches(line)) {
                val current = pending
                if (current != null && current.name.isMeaningful() && !isReceiptTotal(standalone, receiptTotalMinor)) {
                    result += ParsedReceiptLine(
                        description = current.name,
                        quantity = current.quantity,
                        unitPrice = current.unitPrice,
                        totalPrice = standalone,
                        confidence = if (chain == ReceiptChain.UNKNOWN) 0.70f else 0.88f,
                        source = "local-${chain.name.lowercase(Locale.ROOT)}-separated",
                    )
                }
                pending = null
                continue
            }

            if (chain == ReceiptChain.NETTO && pending != null) {
                compactOcrPrice(line)?.let { compact ->
                    result += ParsedReceiptLine(
                        description = pending!!.name,
                        quantity = pending!!.quantity,
                        unitPrice = pending!!.unitPrice,
                        totalPrice = compact,
                        confidence = 0.78f,
                        source = "local-netto-compact-price",
                    )
                    pending = null
                    continue
                }
            }

            if (line.isMeaningful()) {
                val parsedName = parseDescription(line)
                if (parsedName.name.isMeaningful()) pending = parsedName
            }
        }
        return result
    }

    private fun parseDescription(value: String): PendingDescription {
        val clean = value
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+[A-Z]$"), "")
            .trim(' ', '-', ':')
            .fixMeasureOcr()
        val match = quantityPrefix.matchEntire(clean)
        if (match != null) {
            val quantity = match.groupValues[1].replace(',', '.').toDoubleOrNull()
            val name = match.groupValues[2].trim()
            if (quantity != null && name.any(Char::isLetter)) return PendingDescription(name, quantity)
        }
        return PendingDescription(clean)
    }

    private fun shouldIgnore(line: String, chain: ReceiptChain): Boolean {
        if (line.length < 2 || line.none(Char::isLetterOrDigit)) return true
        if (metadata.containsMatchIn(line)) return true
        if (addressLike.containsMatchIn(line)) return true
        if (dateTimePatterns.any { it.containsMatchIn(line) }) return true
        if (Regex("^\\d{4,}\\s+\\d").containsMatchIn(line)) return true
        if (Regex("^V/\\s", RegexOption.IGNORE_CASE).containsMatchIn(line)) return true
        if (chain == ReceiptChain.LIDL && (line.equals("LIDL", true) || line.equals("LGDL", true))) return true
        if (chain == ReceiptChain.NETTO && line.contains("NETTO", true)) return true
        if (chain == ReceiptChain.REMA_1000 && line.contains("REMA 1000", true)) return true
        return false
    }

    private fun looksLikeMerchant(line: String): Boolean =
        line.length in 2..80 && line.any(Char::isLetter) && !metadata.containsMatchIn(line) && !addressLike.containsMatchIn(line)

    private fun compactOcrPrice(line: String): String? {
        val compact = line.trim().replace(" ", "")
        if (!Regex("-?\\d{3,4}").matches(compact)) return null
        val sign = if (compact.startsWith('-')) "-" else ""
        val digits = compact.removePrefix("-")
        val whole = digits.dropLast(2).trimStart('0').ifBlank { "0" }
        return "$sign$whole,${digits.takeLast(2)}"
    }

    private fun isReceiptTotal(value: String, receiptTotalMinor: Long?): Boolean =
        receiptTotalMinor != null && ReceiptMoney.toMinorUnits(value) == receiptTotalMinor

    private fun String.isMeaningful(): Boolean {
        if (length < 2 || none(Char::isLetter)) return false
        if (metadata.containsMatchIn(this)) return false
        if (totalMarker.containsMatchIn(this) || paymentFooter.containsMatchIn(this)) return false
        return true
    }

    private fun String.fixMeasureOcr(): String = split(' ').joinToString(" ") { token ->
        if (token.any(Char::isDigit) && Regex("[0-9Oo]+(?:KG|G|ML|CL|L)", RegexOption.IGNORE_CASE).matches(token)) {
            token.replace('O', '0').replace('o', '0')
        } else token
    }

    private fun normalize(rawText: String): List<String> = rawText
        .lineSequence()
        .map { it.replace('\t', ' ').trim() }
        .map { Regex("\\s+").replace(it, " ") }
        .filter { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
        .toList()

    private fun totalMismatch(total: String?, lines: List<ParsedReceiptLine>): String? {
        val expected = ReceiptMoney.toMinorUnits(total) ?: return null
        if (lines.isEmpty()) return null
        val actual = lines.mapNotNull { ReceiptMoney.toMinorUnits(it.totalPrice) }.sum()
        return if (abs(expected - actual) <= 5L) null else "line_total_mismatch"
    }

    private data class PendingDescription(
        val name: String,
        val quantity: Double? = null,
        val unitPrice: String? = null,
    )
}

internal object ReceiptMoney {
    fun normalize(value: String?): String? {
        val raw = value?.trim().orEmpty()
        val match = Regex("-?\\d{1,7}[,.]\\d{2}").find(raw) ?: return null
        return match.value.replace('.', ',')
    }

    fun toMinorUnits(value: String?): Long? {
        val normalized = normalize(value) ?: return null
        return normalized.replace(',', '.').toBigDecimalOrNull()
            ?.movePointRight(2)
            ?.setScale(0, RoundingMode.HALF_UP)
            ?.longValueExact()
    }

    fun decimal(value: String): String = requireNotNull(normalize(value))
        .replace(',', '.')
        .let(::BigDecimal)
        .stripTrailingZeros()
        .toPlainString()
}

internal fun normalizeProductIdentity(value: String): String {
    var normalized = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .replace('ø', 'o')
        .replace('æ', 'a')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
    normalized = normalized
        .replace(Regex("\\b1000\\s*g\\b"), "1kg")
        .replace(Regex("\\b1000\\s*ml\\b"), "1l")
        .replace(Regex("\\b(\\d+)\\s*(kg|g|ml|cl|l)\\b"), "$1$2")
    return normalized
}
