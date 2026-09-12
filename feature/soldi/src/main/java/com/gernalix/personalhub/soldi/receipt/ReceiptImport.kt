package com.gernalix.personalhub.soldi.receipt

import android.content.Context
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceProduct
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.max

/** Editable local receipt before it is committed to finance transactions. */
data class ReceiptImportDraft(
    val merchant: String,
    val dateTime: String?,
    val total: String?,
    val currency: String,
    val rawText: String,
    val lines: List<ReceiptImportLine>,
    val accountId: String? = null,
)

data class ReceiptImportLine(
    val rawDescription: String,
    val description: String,
    val totalPrice: String,
    val confidence: Float,
    val include: Boolean = true,
    val productId: Long? = null,
)

class ReceiptEnrichmentPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("soldi_receipt_import", Context.MODE_PRIVATE)
    fun chatGptEnabled(): Boolean = prefs.getBoolean(KEY_CHATGPT, false)
    fun setChatGptEnabled(value: Boolean) {
        check(prefs.edit().putBoolean(KEY_CHATGPT, value).commit())
    }

    private companion object { const val KEY_CHATGPT = "chatgpt_enrichment_enabled" }
}

/**
 * Learns confirmed receipt spellings locally. Values point to product UUIDs rather than Room ids,
 * so aliases survive finance export/import where products keep their canonical UUID.
 */
class ReceiptProductAliasStore(context: Context) {
    private val prefs = context.getSharedPreferences("soldi_receipt_product_aliases", Context.MODE_PRIVATE)

    fun resolve(rawDescription: String, products: List<FinanceProduct>): FinanceProduct? {
        val key = aliasKey(rawDescription)
        val uuid = prefs.getString(key, null) ?: return null
        return products.firstOrNull { it.uuid == uuid }
    }

    fun remember(rawDescription: String, product: FinanceProduct) {
        val normalized = normalizeProductIdentity(rawDescription)
        if (normalized.isBlank()) return
        check(prefs.edit().putString("alias:$normalized", product.uuid).commit())
    }

    private fun aliasKey(rawDescription: String) = "alias:${normalizeProductIdentity(rawDescription)}"
}

object ReceiptProductMatcher {
    fun match(
        rawDescription: String,
        products: List<FinanceProduct>,
        aliases: ReceiptProductAliasStore? = null,
    ): FinanceProduct? {
        aliases?.resolve(rawDescription, products)?.let { return it }
        val query = normalizeProductIdentity(rawDescription)
        if (query.isBlank()) return null
        products.firstOrNull { normalizeProductIdentity(it.name) == query }?.let { return it }

        return products
            .map { it to similarity(query, normalizeProductIdentity(it.name)) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 0.92 }
            ?.first
    }

    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val tokenA = a.split(' ').filter(String::isNotBlank).toSet()
        val tokenB = b.split(' ').filter(String::isNotBlank).toSet()
        val union = tokenA union tokenB
        val tokenScore = if (union.isEmpty()) 0.0 else (tokenA intersect tokenB).size.toDouble() / union.size
        val distance = levenshtein(a, b)
        val editScore = 1.0 - distance.toDouble() / max(a.length, b.length)
        return max(tokenScore, editScore)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}

fun ParsedReceipt.toImportDraft(
    products: List<FinanceProduct>,
    aliases: ReceiptProductAliasStore,
): ReceiptImportDraft = ReceiptImportDraft(
    merchant = merchant,
    dateTime = dateTime,
    total = total,
    currency = currency,
    rawText = rawText,
    lines = lines.map { line ->
        val match = ReceiptProductMatcher.match(line.description, products, aliases)
        ReceiptImportLine(
            rawDescription = line.description,
            description = match?.name ?: line.description,
            totalPrice = line.totalPrice,
            confidence = line.confidence,
            productId = match?.id,
        )
    },
)

/** Explicit share/paste bridge: no API key, account token or automatic network request is stored by PH. */
object ReceiptChatGptBridge {
    fun buildPrompt(draft: ReceiptImportDraft, products: List<FinanceProduct>): String {
        val productJson = JSONArray().apply {
            products.sortedBy { it.id }.forEach { product ->
                put(JSONObject().put("productId", product.id).put("name", product.name))
            }
        }
        val lineJson = JSONArray().apply {
            draft.lines.forEachIndexed { index, line ->
                if (line.include) put(
                    JSONObject()
                        .put("index", index)
                        .put("description", line.rawDescription)
                        .put("price", line.totalPrice)
                        .put("currentProductId", line.productId ?: JSONObject.NULL)
                )
            }
        }
        return """
You are enriching receipt data for PersonalHub. Do not change prices and do not invent purchases.
For each input line, identify the product in the existing catalog only when it is clearly the same real product. Reuse its numeric productId in that case. Otherwise use productId=null and provide a concise canonicalName. Different sizes, flavours or variants are different products. Preserve one output row per input index.
Return JSON only, with exactly this shape:
{"lines":[{"index":0,"productId":47,"canonicalName":"Example product"}]}

Existing products:
${productJson}

Receipt merchant: ${draft.merchant}
Receipt lines:
${lineJson}
        """.trimIndent()
    }

    fun applyResponse(
        response: String,
        draft: ReceiptImportDraft,
        products: List<FinanceProduct>,
    ): ReceiptImportDraft {
        val cleaned = response.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val root = JSONObject(cleaned)
        require(root.keys().asSequence().toSet() == setOf("lines"))
        val rows = root.getJSONArray("lines")
        val updates = mutableMapOf<Int, ReceiptImportLine>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.keys().asSequence().toSet() == setOf("index", "productId", "canonicalName"))
            val index = row.getInt("index")
            require(index in draft.lines.indices && index !in updates)
            val current = draft.lines[index]
            val product = if (row.isNull("productId")) null else {
                val id = row.getLong("productId")
                requireNotNull(products.firstOrNull { it.id == id }) { "Unknown product id" }
            }
            val canonicalName = row.getString("canonicalName").trim()
            require(canonicalName.isNotBlank())
            updates[index] = current.copy(
                description = product?.name ?: canonicalName,
                productId = product?.id,
            )
        }
        return draft.copy(lines = draft.lines.mapIndexed { index, line -> updates[index] ?: line })
    }
}

fun receiptOccurredAt(value: String?): String {
    val zone = ZoneId.systemDefault()
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return Instant.now().toString()

    val dateTimePatterns = listOf(
        DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    )
    dateTimePatterns.forEach { formatter ->
        runCatching { LocalDateTime.parse(text, formatter).atZone(zone).toInstant().toString() }.getOrNull()?.let { return it }
    }

    val danishDate = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM uuuu")
        .toFormatter(Locale.forLanguageTag("da-DK"))
    runCatching { LocalDate.parse(text.replace(".", "").trim(), danishDate).atStartOfDay(zone).toInstant().toString() }
        .getOrNull()?.let { return it }

    return Instant.now().toString()
}
