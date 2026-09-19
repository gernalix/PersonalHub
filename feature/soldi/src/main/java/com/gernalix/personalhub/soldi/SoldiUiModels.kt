package com.gernalix.personalhub.soldi

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gernalix.personalhub.core.database.capsules.soldi.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal enum class SoldiTab { TRANSACTIONS, OVERVIEW, STATISTICS, CHARTS, CALENDAR }
internal enum class EntryKind { EXPENSE, INCOME }
internal enum class BottomDestination { HOME, ACCOUNTS, CATEGORIES, RECURRENCES, MORE }

internal sealed interface SoldiEditor {
    data class Transaction(val draft: TransactionDraft, val kind: EntryKind) : SoldiEditor
    data class Transfer(val state: TransferUiState) : SoldiEditor
}

internal data class TransferUiState(
    val transferId: String? = null,
    val sourceTransactionId: Long? = null,
    val crossCurrency: Boolean = false,
    val title: String = "Trasferimento",
    val sourceAccountId: String? = null,
    val targetAccountId: String? = null,
    val sourceAmount: String = "",
    val targetAmount: String = "",
    val quotedRate: String = "",
    val feeAmount: String = "",
    val feeCurrency: String = "",
    val notes: String = "",
    val category: String = "",
    val tags: String = "",
    val personId: Long? = null,
    val occurredAt: String = Instant.now().toString(),
    val reminderAt: String? = null,
)

internal data class ViewOptions(
    val dailyBalance: Boolean = true,
    val hideFuture: Boolean = false,
    val ignoreTransfers: Boolean = true,
    val showAccountCurrency: Boolean = true,
    val groupByDay: Boolean = true,
)

internal class SoldiViewPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("soldi_view_v2", Context.MODE_PRIVATE)

    fun load() = ViewOptions(
        dailyBalance = prefs.getBoolean("daily_balance", true),
        hideFuture = prefs.getBoolean("hide_future", false),
        ignoreTransfers = prefs.getBoolean("ignore_transfers", true),
        showAccountCurrency = prefs.getBoolean("show_account_currency", true),
        groupByDay = prefs.getBoolean("group_by_day", true),
    )

    fun save(value: ViewOptions) {
        prefs.edit()
            .putBoolean("daily_balance", value.dailyBalance)
            .putBoolean("hide_future", value.hideFuture)
            .putBoolean("ignore_transfers", value.ignoreTransfers)
            .putBoolean("show_account_currency", value.showAccountCurrency)
            .putBoolean("group_by_day", value.groupByDay)
            .apply()
    }
}

internal fun defaultTransaction(accounts: List<FinanceAccount>) = TransactionDraft(
    accountId = accounts.firstOrNull()?.id,
    currency = accounts.firstOrNull()?.currency ?: "DKK",
)

internal fun defaultTransfer(accounts: List<FinanceAccount>): TransferUiState {
    val source = accounts.firstOrNull()
    val target = source?.let { s -> accounts.firstOrNull { it.id != s.id && it.currency == s.currency } }
        ?: source?.let { s -> accounts.firstOrNull { it.id != s.id } }
    val crossCurrency = source != null && target != null && source.currency != target.currency
    return TransferUiState(
        crossCurrency = crossCurrency,
        title = if (crossCurrency) "Cambio valuta" else "Trasferimento",
        sourceAccountId = source?.id,
        targetAccountId = target?.id,
        feeCurrency = target?.currency ?: source?.currency.orEmpty(),
    )
}

internal fun defaultRecurrence(accounts: List<FinanceAccount>): RecurrenceDraft? = accounts.firstOrNull()?.let { account ->
    RecurrenceDraft(
        title = "",
        amount = "",
        currency = account.currency,
        accountId = account.id,
        dayOfMonth = LocalDate.now().dayOfMonth,
        kind = "EXPENSE",
    )
}

internal fun TransactionView.toDraft(tags: List<String>) = TransactionDraft(
    id = value.id,
    title = title,
    isProduct = value.productId != null,
    amount = value.amount,
    currency = value.currency,
    chain = chain.orEmpty(),
    placeId = value.placeId,
    notes = value.notes,
    tags = tags.joinToString(", "),
    fromReceipt = value.fromReceipt,
    occurredAt = Instant.ofEpochMilli(value.occurredAt).toString(),
    accountId = value.accountId,
    productId = value.productId,
    personId = value.personId,
    macroId = value.macroId,
    recurrenceId = value.recurrenceId,
    occurrenceKey = value.occurrenceKey,
    reminderAt = value.reminderAt?.let { Instant.ofEpochMilli(it).toString() },
    category = value.category,
)

internal fun FinanceRecurrence.toDraft(tags: List<String>) = RecurrenceDraft(
    id = id,
    title = title,
    amount = amount,
    currency = currency,
    accountId = accountId,
    personId = personId,
    chain = chain,
    placeId = placeId,
    notes = notes,
    tags = tags.joinToString(", "),
    dayOfMonth = dayOfMonth,
    lastBusinessDay = lastBusinessDay,
    startDate = startDate,
    endDate = endDate,
    reminderDaysBefore = reminderDaysBefore,
    enabled = enabled,
    kind = kind,
    targetAccountId = targetAccountId,
    targetAmount = targetAmount,
    quotedRate = quotedRate,
    feeAmount = feeAmount,
    feeCurrency = feeCurrency,
    category = category,
)

internal fun transferState(
    transfer: FinanceTransfer,
    source: TransactionView,
    target: TransactionView,
    tags: List<String>,
): TransferUiState = TransferUiState(
    transferId = transfer.id,
    sourceTransactionId = source.value.id,
    crossCurrency = source.value.currency != target.value.currency,
    title = source.title,
    sourceAccountId = source.value.accountId,
    targetAccountId = target.value.accountId,
    sourceAmount = BigDecimal(source.value.amount).abs().stripTrailingZeros().toPlainString(),
    targetAmount = BigDecimal(target.value.amount).abs().stripTrailingZeros().toPlainString(),
    quotedRate = transfer.quotedRate.orEmpty(),
    feeAmount = transfer.feeAmount.orEmpty(),
    feeCurrency = transfer.feeCurrency.orEmpty(),
    notes = source.value.notes,
    category = source.value.category,
    tags = tags.joinToString(", "),
    personId = source.value.personId,
    occurredAt = Instant.ofEpochMilli(source.value.occurredAt).toString(),
    reminderAt = source.value.reminderAt?.let { Instant.ofEpochMilli(it).toString() },
)

internal fun signedAmount(value: String, kind: EntryKind): String {
    val amount = BigDecimal(FinanceCapsule.decimal(value)).abs()
    return if (kind == EntryKind.EXPENSE) amount.negate().stripTrailingZeros().toPlainString()
    else amount.stripTrailingZeros().toPlainString()
}

internal fun localDate(iso: String): LocalDate = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
internal fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun money(amount: BigDecimal, currency: String): String {
    val nf = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return when (currency) {
        "EUR" -> "€" + nf.format(amount)
        "USD" -> "$" + nf.format(amount)
        "GBP" -> "£" + nf.format(amount)
        "DKK" -> nf.format(amount) + " kr."
        else -> nf.format(amount) + " " + currency
    }
}

internal fun compactMoney(amount: BigDecimal, currency: String): String {
    val abs = amount.abs()
    val number = if (abs >= BigDecimal(1000)) {
        amount.divide(BigDecimal(1000), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "k"
    } else {
        amount.setScale(0, RoundingMode.HALF_UP).toPlainString()
    }
    return when (currency) {
        "EUR" -> "€$number"
        "USD" -> "$" + number
        "GBP" -> "£$number"
        "DKK" -> "kr.$number"
        else -> "$currency $number"
    }
}

@Composable
internal fun amountColor(amount: BigDecimal): Color = when {
    amount.signum() < 0 -> MaterialTheme.colorScheme.error
    amount.signum() > 0 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun recurrenceScheduleLabel(rule: FinanceRecurrence): String = when {
    rule.lastBusinessDay -> "Mensile · ultimo giorno lavorativo"
    else -> "Mensile · giorno ${rule.dayOfMonth}"
}
