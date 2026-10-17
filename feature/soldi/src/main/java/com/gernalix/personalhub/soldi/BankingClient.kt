package com.gernalix.personalhub.soldi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal enum class BankingFailure { AUTHORIZATION, RATE_LIMIT, OFFLINE, SERVER, INVALID_RESPONSE }
internal class BankingException(val kind: BankingFailure, cause: Throwable? = null) : Exception(kind.name, cause)
internal fun bankingFailureForHttpStatus(code: Int): BankingFailure = when (code) {
    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> BankingFailure.AUTHORIZATION
    429 -> BankingFailure.RATE_LIMIT
    else -> BankingFailure.SERVER
}

internal class BankingRepository(context: Context) {
    private val store = BankingSecureStore(context)

    fun configuration(): BankingConfiguration = store.configuration()
    fun cached(): BankingSnapshot? = runCatching(store::readCache).getOrNull()
    fun configure(baseUrl: String, token: String) = store.saveConfiguration(baseUrl, token)
    fun disconnect() = store.disconnect()

    suspend fun refresh(): BankingSnapshot = withContext(Dispatchers.IO) {
        val config = store.configuration()
        if (!config.configured) throw BankingException(BankingFailure.AUTHORIZATION)
        val token = try { store.readCredential() } catch (error: Exception) {
            throw BankingException(BankingFailure.AUTHORIZATION, error)
        }
        try {
            val (accounts, accountCache) = BankingJson.accounts(get(config.baseUrl + "/accounts", token))
            val old = store.readCache()
            val balances = mutableListOf<BankingBalance>()
            val transactions = mutableListOf<BankingTransaction>()
            val partial = mutableSetOf<String>()
            var cache = accountCache
            accounts.forEach { account ->
                try {
                    val (accountBalances, balanceCache) = BankingJson.balances(account, get(config.baseUrl + "/accounts/${account.id}/balances", token))
                    balances += accountBalances
                    cache = strictest(cache, balanceCache)
                    var page = 1
                    do {
                        val (rows, next, transactionCache) = BankingJson.transactions(account,
                            get(config.baseUrl + "/accounts/${account.id}/transactions?days=90&page=$page&page_size=100", token))
                        transactions += rows
                        cache = strictest(cache, transactionCache)
                        page = next ?: 0
                    } while (page > 0)
                } catch (error: BankingException) {
                    if (error.kind == BankingFailure.AUTHORIZATION || error.kind == BankingFailure.RATE_LIMIT) throw error
                    partial += account.id
                    balances += old?.balances.orEmpty().filter { it.accountId == account.id }
                    transactions += old?.transactions.orEmpty().filter { it.accountId == account.id }
                }
            }
            val snapshot = BankingSnapshot(
                accounts = accounts,
                balances = balances,
                transactions = deduplicateBankingTransactions(transactions),
                cache = if (partial.isEmpty()) cache else cache.copy(stale = true, complete = false, lastError = "partial"),
                partialAccounts = partial,
            )
            store.saveCache(snapshot)
            snapshot
        } catch (error: BankingException) {
            throw error
        } catch (error: IOException) {
            throw BankingException(BankingFailure.OFFLINE, error)
        } catch (error: Exception) {
            throw BankingException(BankingFailure.INVALID_RESPONSE, error)
        }
    }

    private fun get(url: String, token: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PersonalHub/${BuildConfig.VERSION_NAME} Android")
            useCaches = false
        }
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                else -> throw BankingException(bankingFailureForHttpStatus(code), IOException("HTTP $code"))
            }
        } finally { connection.disconnect() }
    }

    private fun strictest(first: BankingCacheInfo, second: BankingCacheInfo): BankingCacheInfo = BankingCacheInfo(
        fetchedAt = listOfNotNull(first.fetchedAt, second.fetchedAt).minOrNull(),
        ageSeconds = listOfNotNull(first.ageSeconds, second.ageSeconds).maxOrNull(),
        stale = first.stale || second.stale,
        complete = first.complete && second.complete,
        lastError = first.lastError ?: second.lastError,
        nextRefreshEligibleAt = listOfNotNull(first.nextRefreshEligibleAt, second.nextRefreshEligibleAt).maxOrNull(),
    )
}
