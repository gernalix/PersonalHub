package com.gernalix.personalhub.soldi

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

internal data class BankingCacheInfo(
    val fetchedAt: Instant?,
    val ageSeconds: Long?,
    val stale: Boolean,
    val complete: Boolean,
    val lastError: String?,
    val nextRefreshEligibleAt: Instant?,
)

internal data class BankingAccount(
    val id: String,
    val bank: String,
    val currencies: List<String>,
    val balanceTypes: List<String>,
)

internal data class BankingBalance(
    val accountId: String,
    val bank: String,
    val type: String,
    val amount: BigDecimal,
    val currency: String,
    val referenceDate: LocalDate?,
    val lastChangedAt: Instant?,
)

internal data class BankingTransaction(
    val id: String,
    val accountId: String,
    val bank: String,
    val amount: BigDecimal,
    val currency: String,
    val direction: String,
    val status: String,
    val date: LocalDate?,
    val description: String?,
)

internal data class BankingSnapshot(
    val accounts: List<BankingAccount>,
    val balances: List<BankingBalance>,
    val transactions: List<BankingTransaction>,
    val cache: BankingCacheInfo,
    val partialAccounts: Set<String> = emptySet(),
)

internal data class BankingFilters(
    val query: String = "",
    val bank: String? = null,
    val accountId: String? = null,
    val currency: String? = null,
    val status: String? = null,
    val days: Int = 30,
)

internal data class BankingTotal(val type: String, val currency: String, val amount: BigDecimal)

internal fun comparableBankingTotals(balances: List<BankingBalance>): List<BankingTotal> = balances
    .filter { it.type == "ITAV" || it.type == "ITBD" }
    .groupBy { it.type to it.currency }
    .map { (key, rows) -> BankingTotal(key.first, key.second, rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.amount }) }
    .sortedWith(compareBy(BankingTotal::currency, BankingTotal::type))

internal fun filterBankingTransactions(
    rows: List<BankingTransaction>,
    filters: BankingFilters,
    today: LocalDate = LocalDate.now(),
): List<BankingTransaction> {
    val query = filters.query.trim().lowercase()
    val cutoff = today.minusDays(filters.days.toLong())
    return deduplicateBankingTransactions(rows).filter { row ->
        (filters.bank == null || row.bank == filters.bank) &&
            (filters.accountId == null || row.accountId == filters.accountId) &&
            (filters.currency == null || row.currency == filters.currency) &&
            (filters.status == null || row.status == filters.status) &&
            (row.date == null || !row.date.isBefore(cutoff)) &&
            (query.isBlank() || listOf(row.description, row.bank, row.currency, row.amount.toPlainString())
                .filterNotNull().any { query in it.lowercase() })
    }.sortedWith(compareByDescending<BankingTransaction> { it.date }.thenByDescending { it.id })
}

internal fun deduplicateBankingTransactions(rows: List<BankingTransaction>): List<BankingTransaction> = rows
    .groupBy { it.accountId to it.id }
    .map { (_, matches) -> matches.maxBy { if (it.status == "BOOK") 1 else 0 } }

internal object BankingJson {
    fun accounts(body: String): Pair<List<BankingAccount>, BankingCacheInfo> {
        val root = JSONObject(body)
        val rows = root.requiredArray("accounts").objects().map { row ->
            BankingAccount(
                id = row.requiredString("account_id"),
                bank = row.requiredString("bank"),
                currencies = row.requiredArray("currencies").strings().map(::currency),
                balanceTypes = row.requiredArray("balance_types").strings().map(::balanceType),
            )
        }
        return rows to cache(root.requiredObject("cache"))
    }

    fun balances(account: BankingAccount, body: String): Pair<List<BankingBalance>, BankingCacheInfo> {
        val root = JSONObject(body)
        require(root.requiredString("account_id") == account.id) { "Mismatched bank account" }
        val rows = root.requiredArray("balances").objects().map { row ->
            BankingBalance(
                accountId = account.id,
                bank = account.bank,
                type = balanceType(row.requiredString("type")),
                amount = amount(row.requiredString("amount")),
                currency = currency(row.requiredString("currency")),
                referenceDate = row.optionalString("reference_date")?.let(LocalDate::parse),
                lastChangedAt = row.optionalString("last_changed_at")?.let(::instant),
            )
        }
        return rows to cache(root.requiredObject("cache"))
    }

    fun transactions(account: BankingAccount, body: String): Triple<List<BankingTransaction>, Int?, BankingCacheInfo> {
        val root = JSONObject(body)
        require(root.requiredString("account_id") == account.id) { "Mismatched bank account" }
        val rows = root.requiredArray("transactions").objects().map { row ->
            val direction = row.requiredString("direction").also { require(it == "CRDT" || it == "DBIT") }
            val status = row.requiredString("status").also { require(it == "BOOK" || it == "PDNG") }
            val parsedAmount = amount(row.requiredString("amount"))
            require((direction == "DBIT" && parsedAmount.signum() <= 0) || (direction == "CRDT" && parsedAmount.signum() >= 0)) {
                "Invalid signed bank amount"
            }
            BankingTransaction(
                id = row.requiredString("transaction_id"),
                accountId = account.id,
                bank = account.bank,
                amount = parsedAmount,
                currency = currency(row.requiredString("currency")),
                direction = direction,
                status = status,
                date = listOf("booking_date", "transaction_date", "value_date")
                    .firstNotNullOfOrNull { key -> row.optionalString(key)?.let(LocalDate::parse) },
                description = row.optionalString("description"),
            )
        }
        val pagination = root.requiredObject("pagination")
        val next = pagination.opt("next_page").takeUnless { it == null || it == JSONObject.NULL }?.let {
            (it as Number).toInt().also { value -> require(value > 1) }
        }
        return Triple(rows, next, cache(root.requiredObject("cache")))
    }

    fun encode(snapshot: BankingSnapshot): String = JSONObject().apply {
        put("accounts", JSONArray(snapshot.accounts.map { account -> JSONObject().apply {
            put("id", account.id); put("bank", account.bank)
            put("currencies", JSONArray(account.currencies)); put("balance_types", JSONArray(account.balanceTypes))
        } }))
        put("balances", JSONArray(snapshot.balances.map { row -> JSONObject().apply {
            put("account_id", row.accountId); put("bank", row.bank); put("type", row.type)
            put("amount", row.amount.toPlainString()); put("currency", row.currency)
            put("reference_date", row.referenceDate?.toString()); put("last_changed_at", row.lastChangedAt?.toString())
        } }))
        put("transactions", JSONArray(snapshot.transactions.map { row -> JSONObject().apply {
            put("id", row.id); put("account_id", row.accountId); put("bank", row.bank)
            put("amount", row.amount.toPlainString()); put("currency", row.currency)
            put("direction", row.direction); put("status", row.status); put("date", row.date?.toString())
            put("description", row.description)
        } }))
        put("cache", encodeCache(snapshot.cache)); put("partial_accounts", JSONArray(snapshot.partialAccounts.toList()))
    }.toString()

    fun decode(body: String): BankingSnapshot {
        val root = JSONObject(body)
        val accounts = root.requiredArray("accounts").objects().map { row ->
            BankingAccount(row.requiredString("id"), row.requiredString("bank"), row.requiredArray("currencies").strings(), row.requiredArray("balance_types").strings())
        }
        val balances = root.requiredArray("balances").objects().map { row ->
            BankingBalance(row.requiredString("account_id"), row.requiredString("bank"), balanceType(row.requiredString("type")),
                amount(row.requiredString("amount")), currency(row.requiredString("currency")),
                row.optionalString("reference_date")?.let(LocalDate::parse), row.optionalString("last_changed_at")?.let(::instant))
        }
        val transactions = root.requiredArray("transactions").objects().map { row ->
            BankingTransaction(row.requiredString("id"), row.requiredString("account_id"), row.requiredString("bank"),
                amount(row.requiredString("amount")), currency(row.requiredString("currency")), row.requiredString("direction"),
                row.requiredString("status"), row.optionalString("date")?.let(LocalDate::parse), row.optionalString("description"))
        }
        return BankingSnapshot(accounts, balances, transactions, cache(root.requiredObject("cache")),
            root.requiredArray("partial_accounts").strings().toSet())
    }

    private fun encodeCache(value: BankingCacheInfo) = JSONObject().apply {
        put("fetched_at", value.fetchedAt?.toString()); put("age_seconds", value.ageSeconds)
        put("stale", value.stale); put("complete", value.complete); put("last_error", value.lastError)
        put("next_refresh_eligible_at", value.nextRefreshEligibleAt?.toString())
    }

    private fun cache(value: JSONObject) = BankingCacheInfo(
        fetchedAt = value.optionalString("fetched_at")?.let(::instant),
        ageSeconds = value.opt("age_seconds").takeUnless { it == null || it == JSONObject.NULL }?.let { (it as Number).toLong() },
        stale = value.getBoolean("stale"), complete = value.getBoolean("complete"),
        lastError = value.optionalString("last_error"),
        nextRefreshEligibleAt = value.optionalString("next_refresh_eligible_at")?.let(::instant),
    )

    private fun instant(value: String): Instant = Instant.parse(value)
    private fun amount(value: String) = BigDecimal(value).also { require(it.scale() <= 12 && it.precision() <= 40) }
    private fun currency(value: String) = value.also { require(Regex("[A-Z]{3}").matches(it)) }
    private fun balanceType(value: String) = value.also { require(Regex("[A-Z]{4}").matches(it)) }
}

private fun JSONObject.requiredString(key: String): String = getString(key).also { require(it.isNotBlank()) }
private fun JSONObject.optionalString(key: String): String? = opt(key).takeUnless { it == null || it == JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)
private fun JSONObject.requiredArray(key: String): JSONArray = getJSONArray(key)
private fun JSONObject.requiredObject(key: String): JSONObject = getJSONObject(key)
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
