package com.gernalix.personalhub.soldi

import android.content.Context
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionDraft
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
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

/** Reject non-advancing or runaway pagination instead of repeatedly requesting the same page. */
internal fun checkedBankingNextPage(current: Int, next: Int?): Int {
    if (next == null) return 0
    if (next <= current || next > 100) throw BankingException(BankingFailure.INVALID_RESPONSE)
    return next
}

internal class BankingRepository(context: Context) {
    private val app = context.applicationContext
    private val store = BankingSecureStore(app)

    fun configuration(): BankingConfiguration = store.configuration()
    fun cached(): BankingSnapshot? = runCatching(store::readCache).getOrNull()
    fun configure(baseUrl: String, token: String) = store.saveConfiguration(baseUrl, token)
    fun disconnect() = store.disconnect()
    fun reconciliationState(): BankingReconciliationState =
        runCatching(store::reconciliationState).getOrDefault(BankingReconciliationState())

    fun linkAccount(bankAccount: BankingAccount, localAccount: FinanceAccount): BankingReconciliationState {
        require(localAccount.currency in bankAccount.currencies) { "Bank and PersonalHub currencies differ" }
        return store.updateReconciliationState { state ->
            require(state.accountLinks.none { (bankId, localId) ->
                bankId != bankAccount.id && localId == localAccount.id
            }) { "PersonalHub account is already linked" }
            state.copy(accountLinks = state.accountLinks + (bankAccount.id to localAccount.id))
        }
    }

    fun unlinkAccount(bankAccountId: String): BankingReconciliationState = store.updateReconciliationState { state ->
        state.copy(
            accountLinks = state.accountLinks - bankAccountId,
            observedAlerts = state.observedAlerts - bankAccountId,
            notifiedAlerts = state.notifiedAlerts - bankAccountId,
        )
    }

    fun saveAlertPreferences(enabled: Boolean, threshold: BigDecimal): BankingReconciliationState {
        require(!enabled || threshold.signum() > 0) { "Alert threshold must be positive" }
        return store.updateReconciliationState { state ->
            state.copy(
                alertPreferences = BankingAlertPreferences(enabled, threshold.stripTrailingZeros()),
                observedAlerts = if (enabled) state.observedAlerts else emptyMap(),
                notifiedAlerts = if (enabled) state.notifiedAlerts else emptyMap(),
            )
        }
    }

    fun validateSuggestion(
        suggestion: BankingSuggestion,
        draft: TransactionDraft,
        localAccounts: List<FinanceAccount>,
        localRows: List<TransactionView>,
    ): Boolean {
        val snapshot = cached() ?: return false
        return validateBankingSuggestion(suggestion, draft, snapshot, reconciliationState(), localAccounts, localRows)
    }

    fun markSuggestionAccepted(suggestion: BankingSuggestion, transactionUuid: String) {
        store.updateReconciliationState { state ->
            require(suggestion.key !in state.acceptedSuggestions) { "Bank suggestion was already accepted" }
            state.copy(acceptedSuggestions = state.acceptedSuggestions + (suggestion.key to transactionUuid))
        }
    }

    fun processAlerts(
        snapshot: BankingSnapshot,
        localAccounts: List<FinanceAccount>,
        localRows: List<TransactionView>,
    ): BankingReconciliationState {
        val initial = reconciliationState()
        val result = reconcileBanking(snapshot, initial, localAccounts, localRows)
        val advance = advanceBankingAlerts(initial, result.comparisons)
        var saved = store.updateReconciliationState { current ->
            current.copy(
                observedAlerts = advance.state.observedAlerts,
                notifiedAlerts = advance.state.notifiedAlerts,
            )
        }
        val posted = advance.accountIdsToNotify.filter { BankingAlertNotifier.post(app, it) }
        if (posted.isNotEmpty()) {
            saved = store.updateReconciliationState { current ->
                current.copy(notifiedAlerts = current.notifiedAlerts + posted.mapNotNull { accountId ->
                    current.observedAlerts[accountId]?.let { accountId to it }
                })
            }
        }
        return saved
    }

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
                        page = checkedBankingNextPage(page, next)
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
