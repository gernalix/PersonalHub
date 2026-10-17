package com.gernalix.personalhub.soldi

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gernalix.personalhub.core.alerts.HubNotificationPlatform
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransaction
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionDraft
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal data class BankingAlertPreferences(
    val enabled: Boolean = false,
    val threshold: BigDecimal = BigDecimal.ZERO,
)

internal data class BankingReconciliationState(
    val accountLinks: Map<String, String> = emptyMap(),
    val alertPreferences: BankingAlertPreferences = BankingAlertPreferences(),
    val acceptedSuggestions: Map<String, String> = emptyMap(),
    val observedAlerts: Map<String, String> = emptyMap(),
    val notifiedAlerts: Map<String, String> = emptyMap(),
)

internal enum class BankingComparisonState {
    COMPARABLE,
    UNMAPPED,
    MAPPING_INVALID,
    CURRENCY_MISMATCH,
    STALE,
    PARTIAL,
    INCOMPLETE,
    NO_BOOKED_BALANCE,
    REFERENCE_DATE_MISMATCH,
    LOCAL_ACCOUNT_NOT_OPEN,
}

internal enum class BankingComparisonCause {
    BALANCED,
    MISSING_PH_TRANSACTIONS,
    PENDING_SEPARATE,
    FUTURE_PH_SEPARATE,
    UNCONFIRMED,
}

internal data class BankingComparison(
    val bankAccountId: String,
    val localAccountId: String?,
    val state: BankingComparisonState,
    val currency: String? = null,
    val bankBalance: BigDecimal? = null,
    val phBalance: BigDecimal? = null,
    val difference: BigDecimal? = null,
    val fetchedAt: Instant? = null,
    val pendingCount: Int = 0,
    val futurePhCount: Int = 0,
    val suggestionCount: Int = 0,
    val ambiguousCount: Int = 0,
    val cause: BankingComparisonCause? = null,
)

internal data class BankingSuggestion(
    val bankAccountId: String,
    val providerTransactionId: String,
    val localAccountId: String,
    val bank: String,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate,
    val description: String,
) {
    val key: String get() = "$bankAccountId:$providerTransactionId"

    fun toEditor(): SoldiEditor.Transaction = SoldiEditor.Transaction(
        draft = TransactionDraft(
            title = description,
            amount = amount.abs().stripTrailingZeros().toPlainString(),
            currency = currency,
            occurredAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
            accountId = localAccountId,
        ),
        kind = if (amount.signum() < 0) EntryKind.EXPENSE else EntryKind.INCOME,
    )
}

internal data class BankingTransactionAnalysis(
    val suggestions: List<BankingSuggestion>,
    val ambiguousByAccount: Map<String, Int>,
)

internal data class BankingReconciliationResult(
    val comparisons: List<BankingComparison>,
    val suggestions: List<BankingSuggestion>,
)

internal fun reconcileBanking(
    snapshot: BankingSnapshot,
    state: BankingReconciliationState,
    localAccounts: List<FinanceAccount>,
    localRows: List<TransactionView>,
    now: Instant = Instant.now(),
): BankingReconciliationResult {
    val localById = localAccounts.associateBy(FinanceAccount::id)
    val rawRows = localRows.map(TransactionView::value)
    val analysis = analyzeBankingTransactions(snapshot, state, localById, localRows, now)
    val fetchedAt = snapshot.cache.fetchedAt
    val fetchedDate = fetchedAt?.atZone(ZoneOffset.UTC)?.toLocalDate()

    val comparisons = snapshot.accounts.map { bankAccount ->
        val localId = state.accountLinks[bankAccount.id]
            ?: return@map BankingComparison(bankAccount.id, null, BankingComparisonState.UNMAPPED)
        val local = localById[localId]
            ?: return@map BankingComparison(bankAccount.id, localId, BankingComparisonState.MAPPING_INVALID)
        if (local.currency !in bankAccount.currencies) {
            return@map BankingComparison(bankAccount.id, localId, BankingComparisonState.CURRENCY_MISMATCH, local.currency)
        }
        if (snapshot.partialAccounts.contains(bankAccount.id)) {
            return@map BankingComparison(bankAccount.id, localId, BankingComparisonState.PARTIAL, local.currency)
        }
        if (!snapshot.cache.complete) {
            return@map BankingComparison(bankAccount.id, localId, BankingComparisonState.INCOMPLETE, local.currency)
        }
        if (fetchedAt == null || bankingCacheIsStale(snapshot.cache, now)) {
            return@map BankingComparison(bankAccount.id, localId, BankingComparisonState.STALE, local.currency, fetchedAt = fetchedAt)
        }
        if (local.openedAt > fetchedAt.toEpochMilli()) {
            return@map BankingComparison(bankAccount.id, localId, BankingComparisonState.LOCAL_ACCOUNT_NOT_OPEN, local.currency, fetchedAt = fetchedAt)
        }
        val booked = snapshot.balances.filter {
            it.accountId == bankAccount.id && it.type == "ITBD" && it.currency == local.currency
        }
        if (booked.size != 1) {
            return@map BankingComparison(bankAccount.id, localId, BankingComparisonState.NO_BOOKED_BALANCE, local.currency, fetchedAt = fetchedAt)
        }
        val bankBalance = booked.single()
        if (bankBalance.referenceDate == null || bankBalance.referenceDate != fetchedDate) {
            return@map BankingComparison(bankAccount.id, localId, BankingComparisonState.REFERENCE_DATE_MISMATCH, local.currency, fetchedAt = fetchedAt)
        }

        val phBalance = FinanceCapsule.balance(local, rawRows, fetchedAt)
        val difference = bankBalance.amount.subtract(phBalance)
        val suggestions = analysis.suggestions.filter { it.bankAccountId == bankAccount.id }
        val pendingCount = snapshot.transactions.count {
            it.accountId == bankAccount.id && it.currency == local.currency && it.status == "PDNG"
        }
        val futureCount = rawRows.count { it.accountId == local.id && it.occurredAt > fetchedAt.toEpochMilli() }
        val suggestedTotal = suggestions.fold(BigDecimal.ZERO) { sum, row -> sum + row.amount }
        val cause = when {
            difference.signum() == 0 -> BankingComparisonCause.BALANCED
            suggestions.isNotEmpty() && suggestedTotal.compareTo(difference) == 0 -> BankingComparisonCause.MISSING_PH_TRANSACTIONS
            pendingCount > 0 -> BankingComparisonCause.PENDING_SEPARATE
            futureCount > 0 -> BankingComparisonCause.FUTURE_PH_SEPARATE
            else -> BankingComparisonCause.UNCONFIRMED
        }
        BankingComparison(
            bankAccountId = bankAccount.id,
            localAccountId = local.id,
            state = BankingComparisonState.COMPARABLE,
            currency = local.currency,
            bankBalance = bankBalance.amount,
            phBalance = phBalance,
            difference = difference,
            fetchedAt = fetchedAt,
            pendingCount = pendingCount,
            futurePhCount = futureCount,
            suggestionCount = suggestions.size,
            ambiguousCount = analysis.ambiguousByAccount[bankAccount.id] ?: 0,
            cause = cause,
        )
    }
    return BankingReconciliationResult(comparisons, analysis.suggestions)
}

internal fun analyzeBankingTransactions(
    snapshot: BankingSnapshot,
    state: BankingReconciliationState,
    localAccounts: Map<String, FinanceAccount>,
    localRows: List<TransactionView>,
    now: Instant = Instant.now(),
): BankingTransactionAnalysis {
    if (!snapshot.cache.complete || bankingCacheIsStale(snapshot.cache, now) || snapshot.cache.fetchedAt == null) {
        return BankingTransactionAnalysis(emptyList(), emptyMap())
    }
    val suggestions = mutableListOf<BankingSuggestion>()
    val ambiguous = mutableMapOf<String, Int>()
    snapshot.transactions
        .asSequence()
        .filter { it.status == "BOOK" && it.date != null && !it.description.isNullOrBlank() }
        .distinctBy { it.accountId to it.id }
        .forEach { bankRow ->
            if (snapshot.partialAccounts.contains(bankRow.accountId)) return@forEach
            val localId = state.accountLinks[bankRow.accountId] ?: return@forEach
            val local = localAccounts[localId] ?: return@forEach
            if (local.currency != bankRow.currency) return@forEach
            val key = "${bankRow.accountId}:${bankRow.id}"
            if (key in state.acceptedSuggestions) return@forEach
            val bankDate = requireNotNull(bankRow.date)
            val plausible = localRows.filter { localRow ->
                val value = localRow.value
                value.accountId == local.id && value.currency == bankRow.currency &&
                    BigDecimal(value.amount).compareTo(bankRow.amount) == 0 &&
                    kotlin.math.abs(ChronoUnit.DAYS.between(
                        Instant.ofEpochMilli(value.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate(),
                        bankDate,
                    )) <= 1
            }
            when {
                plausible.size > 1 -> ambiguous[bankRow.accountId] = (ambiguous[bankRow.accountId] ?: 0) + 1
                plausible.isEmpty() -> suggestions += BankingSuggestion(
                    bankAccountId = bankRow.accountId,
                    providerTransactionId = bankRow.id,
                    localAccountId = local.id,
                    bank = bankRow.bank,
                    amount = bankRow.amount,
                    currency = bankRow.currency,
                    date = bankDate,
                    description = requireNotNull(bankRow.description).trim(),
                )
            }
        }
    return BankingTransactionAnalysis(suggestions.sortedByDescending(BankingSuggestion::date), ambiguous)
}

internal fun validateBankingSuggestion(
    suggestion: BankingSuggestion,
    draft: TransactionDraft,
    snapshot: BankingSnapshot,
    state: BankingReconciliationState,
    localAccounts: List<FinanceAccount>,
    localRows: List<TransactionView>,
    now: Instant = Instant.now(),
): Boolean {
    val result = reconcileBanking(snapshot, state, localAccounts, localRows, now)
    val current = result.suggestions.firstOrNull { it.key == suggestion.key } ?: return false
    val comparison = result.comparisons.firstOrNull { it.bankAccountId == suggestion.bankAccountId } ?: return false
    val draftDate = runCatching {
        Instant.parse(draft.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull() ?: return false
    val draftAmount = runCatching { BigDecimal(draft.amount) }.getOrNull() ?: return false
    return comparison.state == BankingComparisonState.COMPARABLE && comparison.difference?.signum() != 0 &&
        draft.accountId == current.localAccountId && draft.currency == current.currency &&
        draftAmount.compareTo(current.amount) == 0 && draftDate == current.date
}

internal data class BankingAlertAdvance(
    val state: BankingReconciliationState,
    val accountIdsToNotify: List<String>,
)

internal fun advanceBankingAlerts(
    state: BankingReconciliationState,
    comparisons: List<BankingComparison>,
): BankingAlertAdvance {
    if (!state.alertPreferences.enabled) {
        return BankingAlertAdvance(state.copy(observedAlerts = emptyMap(), notifiedAlerts = emptyMap()), emptyList())
    }
    val observed = state.observedAlerts.toMutableMap()
    val notified = state.notifiedAlerts.toMutableMap()
    val toNotify = mutableListOf<String>()
    comparisons.forEach { comparison ->
        if (comparison.state != BankingComparisonState.COMPARABLE) return@forEach
        val difference = comparison.difference
        val eligible = difference != null && difference.signum() != 0 &&
            difference.abs().compareTo(state.alertPreferences.threshold) >= 0
        if (!eligible) {
            observed.remove(comparison.bankAccountId)
            notified.remove(comparison.bankAccountId)
            return@forEach
        }
        val fingerprint = listOf(
            comparison.currency,
            comparison.bankBalance?.stripTrailingZeros()?.toPlainString(),
            comparison.phBalance?.stripTrailingZeros()?.toPlainString(),
            difference.stripTrailingZeros().toPlainString(),
        ).joinToString("|")
        if (observed[comparison.bankAccountId] == fingerprint) {
            if (notified[comparison.bankAccountId] != fingerprint) toNotify += comparison.bankAccountId
        } else {
            observed[comparison.bankAccountId] = fingerprint
            notified.remove(comparison.bankAccountId)
        }
    }
    return BankingAlertAdvance(state.copy(observedAlerts = observed, notifiedAlerts = notified), toNotify)
}

internal object BankingAlertNotifier {
    private const val CHANNEL_ID = "soldi_banking_discrepancies"

    fun post(context: Context, bankAccountId: String): Boolean {
        if (!HubNotificationPlatform.canPost(context)) return false
        HubNotificationPlatform.ensureChannel(
            context = context,
            id = CHANNEL_ID,
            name = context.getString(R.string.banking_alert_channel),
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            description = context.getString(R.string.banking_alert_channel_description),
        )
        val intent = Intent(Intent.ACTION_VIEW, HubDeepLinkContract.moduleUri("soldi")).setPackage(context.packageName)
        val content = PendingIntent.getActivity(
            context,
            bankAccountId.hashCode(),
            intent,
            HubNotificationPlatform.pendingIntentFlags(),
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.banking_alert_title))
            .setContentText(context.getString(R.string.banking_alert_body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        HubNotificationPlatform.post(context, ("banking:$bankAccountId").hashCode(), notification)
        return true
    }
}
