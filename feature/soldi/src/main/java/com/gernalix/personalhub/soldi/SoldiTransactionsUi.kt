package com.gernalix.personalhub.soldi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gernalix.personalhub.core.database.capsules.soldi.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private sealed interface LedgerEntry {
    val date: LocalDate
    val key: String
}

private data class RowEntry(val row: TransactionView) : LedgerEntry {
    override val date: LocalDate = localDate(row.value.occurredAt)
    override val key: String = "row:${row.value.id}"
}

private data class TransferEntry(
    val transfer: FinanceTransfer,
    val source: TransactionView,
    val target: TransactionView,
) : LedgerEntry {
    override val date: LocalDate = localDate(source.value.occurredAt)
    override val key: String = "transfer:${transfer.id}"
}

private data class MacroEntry(
    val macro: FinanceMacro,
    val children: List<TransactionView>,
) : LedgerEntry {
    override val date: LocalDate = localDate(macro.occurredAt)
    override val key: String = "macro:${macro.id}"
}

@Composable
internal fun TransactionsScreenV2(
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    accounts: List<FinanceAccount>,
    rows: List<TransactionView>,
    transfers: List<FinanceTransfer>,
    macros: List<FinanceMacro>,
    tagsByTransaction: Map<Long, List<String>>,
    options: ViewOptions,
    onEdit: (TransactionView) -> Unit,
    onEditTransfer: (FinanceTransfer, TransactionView, TransactionView) -> Unit,
    onNewExpense: () -> Unit,
    onNewIncome: () -> Unit,
    onNewTransfer: () -> Unit,
) {
    val logicalEntries = remember(month, search, rows, transfers, macros, tagsByTransaction, options.hideFuture) {
        buildLedgerEntries(month, search, rows, transfers, macros, tagsByTransaction, options.hideFuture)
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        MonthHeader(month, onMonth)
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Cerca") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = onNewExpense, label = { Text("Spesa") })
            AssistChip(onClick = onNewIncome, label = { Text("Entrata") })
            AssistChip(onClick = onNewTransfer, label = { Text("Trasferimento") })
        }
        Spacer(Modifier.height(4.dp))
        if (logicalEntries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nessuna transazione") }
        } else if (options.groupByDay) {
            val groups = logicalEntries.groupBy { it.date }.toSortedMap(compareByDescending { it })
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (date, entries) ->
                    item(key = "day:$date") {
                        DayGroupCardV2(
                            date = date,
                            entries = entries,
                            allRows = rows,
                            accountMap = accountMap,
                            tagsByTransaction = tagsByTransaction,
                            showBalance = options.dailyBalance,
                            expanded = expanded,
                            onEdit = onEdit,
                            onEditTransfer = onEditTransfer,
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logicalEntries, key = { it.key }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        LedgerEntryContent(
                            entry = entry,
                            accountMap = accountMap,
                            tagsByTransaction = tagsByTransaction,
                            expanded = expanded,
                            onEdit = onEdit,
                            onEditTransfer = onEditTransfer,
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

private fun buildLedgerEntries(
    month: YearMonth,
    search: String,
    rows: List<TransactionView>,
    transfers: List<FinanceTransfer>,
    macros: List<FinanceMacro>,
    tagsByTransaction: Map<Long, List<String>>,
    hideFuture: Boolean,
): List<LedgerEntry> {
    val now = Instant.now()
    val monthRows = rows.filter { row ->
        YearMonth.from(localDate(row.value.occurredAt)) == month &&
            (!hideFuture || !Instant.parse(row.value.occurredAt).isAfter(now))
    }
    val rowMap = rows.associateBy { it.value.id }
    val transferMap = buildMap<Long, FinanceTransfer> {
        transfers.forEach { transfer ->
            put(transfer.sourceTransactionId, transfer)
            put(transfer.targetTransactionId, transfer)
        }
    }
    val macroMap = macros.associateBy { it.id }
    val query = search.trim()

    fun matches(row: TransactionView): Boolean = query.isBlank() || listOf(
        row.title,
        row.chain,
        row.place,
        row.person,
        row.value.notes,
        row.value.category,
        tagsByTransaction[row.value.id]?.joinToString(" "),
    ).any { it?.contains(query, ignoreCase = true) == true }

    val result = mutableListOf<LedgerEntry>()
    val handledTransfers = mutableSetOf<String>()
    val handledMacros = mutableSetOf<String>()

    monthRows.forEach { row ->
        row.value.macroId?.let { macroId ->
            if (macroId !in handledMacros) {
                val allChildren = monthRows.filter { it.value.macroId == macroId }
                val macro = macroMap[macroId]
                val include = query.isBlank() || macro?.title?.contains(query, true) == true || allChildren.any(::matches)
                if (macro != null && include) result += MacroEntry(macro, allChildren)
                handledMacros += macroId
            }
            return@forEach
        }

        val transfer = transferMap[row.value.id]
        if (transfer != null) {
            if (transfer.id !in handledTransfers) {
                val source = rowMap[transfer.sourceTransactionId]
                val target = rowMap[transfer.targetTransactionId]
                if (source != null && target != null) {
                    val include = query.isBlank() || matches(source) || matches(target) || "trasferimento cambio valuta".contains(query, true)
                    if (include && YearMonth.from(localDate(source.value.occurredAt)) == month) result += TransferEntry(transfer, source, target)
                }
                handledTransfers += transfer.id
            }
            return@forEach
        }

        if (matches(row)) result += RowEntry(row)
    }
    return result.sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.key })
}

@Composable
private fun DayGroupCardV2(
    date: LocalDate,
    entries: List<LedgerEntry>,
    allRows: List<TransactionView>,
    accountMap: Map<String, FinanceAccount>,
    tagsByTransaction: Map<Long, List<String>>,
    showBalance: Boolean,
    expanded: MutableMap<String, Boolean>,
    onEdit: (TransactionView) -> Unit,
    onEditTransfer: (FinanceTransfer, TransactionView, TransactionView) -> Unit,
) {
    val rawDayRows = allRows.filter { localDate(it.value.occurredAt) == date }
    val dayTotals = rawDayRows.groupBy { it.value.currency }.mapValues { (_, list) ->
        list.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.value.amount) }
    }
    val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1)
    val balances = FinanceCapsule.totals(accountMap.values.toList(), allRows.map { it.value }, end)

    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(date.dayOfMonth.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(date.dayOfWeek.getDisplayName(TextStyle.FULL, locale), fontWeight = FontWeight.SemiBold)
                    if (showBalance) {
                        Text(
                            balances.entries.joinToString(" · ") { compactMoney(it.value, it.key) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    dayTotals.forEach { (currency, amount) ->
                        Text(money(amount, currency), color = amountColor(amount), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
            HorizontalDivider()
            entries.forEach { entry ->
                LedgerEntryContent(entry, accountMap, tagsByTransaction, expanded, onEdit, onEditTransfer)
            }
        }
    }
}

@Composable
private fun LedgerEntryContent(
    entry: LedgerEntry,
    accountMap: Map<String, FinanceAccount>,
    tagsByTransaction: Map<Long, List<String>>,
    expanded: MutableMap<String, Boolean>,
    onEdit: (TransactionView) -> Unit,
    onEditTransfer: (FinanceTransfer, TransactionView, TransactionView) -> Unit,
) {
    when (entry) {
        is RowEntry -> {
            val row = entry.row
            val details = listOfNotNull(
                row.value.category.takeIf(String::isNotBlank),
                row.chain,
                row.place,
                row.person,
                tagsByTransaction[row.value.id]?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            ).joinToString(" · ")
            TransactionLineV2(
                title = row.title,
                subtitle = details,
                amount = money(BigDecimal(row.value.amount), row.value.currency),
                amountValue = BigDecimal(row.value.amount),
                leading = if (BigDecimal(row.value.amount).signum() < 0) "−" else "+",
                onClick = { onEdit(row) },
            )
        }
        is TransferEntry -> {
            val sourceAmount = BigDecimal(entry.source.value.amount)
            val targetAmount = BigDecimal(entry.target.value.amount)
            val sameCurrency = entry.source.value.currency == entry.target.value.currency
            val title = if (sameCurrency) "Trasferimento" else "Cambio valuta"
            val subtitle = listOfNotNull(
                accountMap[entry.source.value.accountId]?.name,
                accountMap[entry.target.value.accountId]?.name,
            ).joinToString(" → ")
            TransactionLineV2(
                title = title,
                subtitle = subtitle,
                amount = "${money(sourceAmount, entry.source.value.currency)} → ${money(targetAmount, entry.target.value.currency)}",
                amountValue = BigDecimal.ZERO,
                leading = "⇄",
                neutralAmount = true,
                onClick = { onEditTransfer(entry.transfer, entry.source, entry.target) },
            )
        }
        is MacroEntry -> {
            val totalByCurrency = entry.children.groupBy { it.value.currency }.mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.value.amount) }
            }
            TransactionLineV2(
                title = entry.macro.title,
                subtitle = "${entry.children.size} acquisti · tocca per espandere",
                amount = totalByCurrency.entries.joinToString(" · ") { money(it.value, it.key) },
                amountValue = totalByCurrency.values.fold(BigDecimal.ZERO, BigDecimal::add),
                leading = "▣",
                onClick = { expanded[entry.macro.id] = !(expanded[entry.macro.id] ?: false) },
            )
            if (expanded[entry.macro.id] == true) {
                entry.children.forEach { child ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 28.dp).clickable { onEdit(child) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("└", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(child.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val detail = listOfNotNull(
                                child.value.category.takeIf(String::isNotBlank),
                                tagsByTransaction[child.value.id]?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                            ).joinToString(" · ")
                            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(BigDecimal(child.value.amount), child.value.currency), color = amountColor(BigDecimal(child.value.amount)), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionLineV2(
    title: String,
    subtitle: String,
    amount: String,
    amountValue: BigDecimal,
    leading: String,
    neutralAmount: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(leading, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            amount,
            color = if (neutralAmount) MaterialTheme.colorScheme.onSurface else amountColor(amountValue),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}
