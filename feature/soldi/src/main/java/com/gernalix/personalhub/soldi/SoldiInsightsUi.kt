package com.gernalix.personalhub.soldi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gernalix.personalhub.core.database.capsules.soldi.*
import com.gernalix.personalhub.core.ui.HubTimeFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal data class StatSlice(val name: String, val amount: BigDecimal)

@Composable
internal fun OverviewScreenV2(
    month: YearMonth,
    accounts: List<FinanceAccount>,
    rows: List<FinanceTransaction>,
    projected: List<ProjectedOccurrence>,
    transfers: List<FinanceTransfer>,
    ignoreTransfers: Boolean,
) {
    val now = java.time.Instant.now()
    val current = FinanceCapsule.totals(accounts, rows, now)
    val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1)
    val forecast = FinanceCapsule.projectedTotals(accounts, rows, monthEnd, projected)
    val transferIds = transfers.flatMap { listOf(it.sourceTransactionId, it.targetTransactionId) }.toSet()
    val monthRows = rows.filter {
        YearMonth.from(localDate(it.occurredAt)) == month && (!ignoreTransfers || it.id !in transferIds)
    }
    val cashByCurrency = monthRows.groupBy { it.currency }.toSortedMap().mapValues { (_, currencyRows) ->
        Triple(
            currencyRows.filter { BigDecimal(it.amount).signum() > 0 }.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.amount) },
            currencyRows.filter { BigDecimal(it.amount).signum() < 0 }.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.amount) },
            currencyRows.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.amount) },
        )
    }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MonthTitle(month) }
        item { BalanceCard("Saldo per valuta (oggi)", current) }
        item { BalanceCard("Saldo previsto fine mese", forecast) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Entrate / Uscite (questo mese)", fontWeight = FontWeight.SemiBold)
                    if (cashByCurrency.isEmpty()) Text("Nessun movimento", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    cashByCurrency.forEach { (currency, values) ->
                        Text(currency, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        CashLine("Entrate", values.first, currency)
                        CashLine("Uscite", values.second, currency)
                        CashLine("Cash flow", values.third, currency)
                    }
                    Text(
                        "Le valute non vengono mai sommate tra loro. I trasferimenti interni sono ${if (ignoreTransfers) "esclusi" else "inclusi"} dal cash flow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(title: String, totals: Map<String, BigDecimal>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (totals.isEmpty()) Text("Nessun conto incluso")
            totals.forEach { (currency, amount) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(currency, Modifier.weight(1f))
                    Text(money(amount, currency), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CashLine(label: String, amount: BigDecimal, currency: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(money(amount, currency), color = amountColor(amount), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun StatisticsScreenV2(
    month: YearMonth,
    rows: List<TransactionView>,
    transfers: List<FinanceTransfer>,
    tagsByTransaction: Map<Long, List<String>>,
) {
    val transferIds = remember(transfers) { transfers.flatMap { listOf(it.sourceTransactionId, it.targetTransactionId) }.toSet() }
    val expenses = rows.filter {
        YearMonth.from(localDate(it.value.occurredAt)) == month &&
            BigDecimal(it.value.amount).signum() < 0 && it.value.id !in transferIds
    }
    val currencies = expenses.map { it.value.currency }.distinct().sorted()
    var selectedCurrency by remember(currencies) { mutableStateOf(currencies.firstOrNull()) }
    val currency = selectedCurrency ?: currencies.firstOrNull()
    val selected = if (currency == null) emptyList() else expenses.filter { it.value.currency == currency }
    val total = selected.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.value.amount).abs() }
    val slices = selected.groupBy { it.category.ifBlank { "Senza categoria" } }
        .map { (name, group) -> StatSlice(name, group.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.value.amount).abs() }) }
        .sortedByDescending { it.amount }
    val tagSlices = selected.flatMap { row ->
        tagsByTransaction[row.value.id].orEmpty().distinct().map { tag -> tag to BigDecimal(row.value.amount).abs() }
    }.groupBy({ it.first }, { it.second })
        .map { (name, amounts) -> StatSlice(name, amounts.fold(BigDecimal.ZERO, BigDecimal::add)) }
        .sortedByDescending { it.amount }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { MonthTitle(month) }
        if (currencies.size > 1) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    currencies.forEach { code -> FilterChip(selected = code == currency, onClick = { selectedCurrency = code }, label = { Text(code) }) }
                }
            }
        }
        item {
            Text("Uscite totali", style = MaterialTheme.typography.titleMedium)
            Text(money(total.negate(), currency ?: "DKK"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.headlineSmall)
        }
        items(slices, key = { it.name }) { slice ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(slice.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(money(slice.amount.negate(), currency ?: "DKK"), color = MaterialTheme.colorScheme.error)
                    }
                    if (total.signum() > 0) {
                        val ratio = slice.amount.divide(total, 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(6.dp)) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) { }
                            Surface(Modifier.fillMaxWidth(ratio).fillMaxHeight(), color = MaterialTheme.colorScheme.primary, shape = CircleShape) { }
                        }
                        Text("${slice.amount.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP)}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (tagSlices.isNotEmpty()) item { Text("Spesa per tag", style = MaterialTheme.typography.titleMedium) }
        items(tagSlices, key = { "tag:${it.name}" }) { slice ->
            ListItem(
                headlineContent = { Text("#${slice.name}") },
                trailingContent = { Text(money(slice.amount.negate(), currency ?: "DKK"), color = MaterialTheme.colorScheme.error) },
            )
        }
    }
}

@Composable
internal fun ChartsScreenV2(month: YearMonth, rows: List<TransactionView>, transfers: List<FinanceTransfer>) {
    val transferIds = remember(transfers) { transfers.flatMap { listOf(it.sourceTransactionId, it.targetTransactionId) }.toSet() }
    val expenses = rows.filter {
        YearMonth.from(localDate(it.value.occurredAt)) == month && BigDecimal(it.value.amount).signum() < 0 && it.value.id !in transferIds
    }
    val currencies = expenses.map { it.value.currency }.distinct().sorted()
    var selectedCurrency by remember(currencies) { mutableStateOf(currencies.firstOrNull()) }
    val currency = selectedCurrency ?: currencies.firstOrNull()
    val selected = if (currency == null) emptyList() else expenses.filter { it.value.currency == currency }
    val slices = selected.groupBy { it.category.ifBlank { "Senza categoria" } }
        .map { (name, group) -> StatSlice(name, group.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.value.amount).abs() }) }
        .sortedByDescending { it.amount }
        .take(8)
    val total = slices.fold(BigDecimal.ZERO) { sum, slice -> sum + slice.amount }
    val palette = listOf(
        Color(0xFFFF5B5B), Color(0xFF58D68D), Color(0xFFFFC857), Color(0xFF55B7FF),
        Color(0xFFB388FF), Color(0xFF4DD0E1), Color(0xFFA5D66A), Color(0xFFB0BEC5),
    )

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MonthTitle(month)
        if (currencies.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                currencies.forEach { code -> FilterChip(selected = code == currency, onClick = { selectedCurrency = code }, label = { Text(code) }) }
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                var start = -90f
                slices.forEachIndexed { index, slice ->
                    val sweep = if (total.signum() == 0) 0f else slice.amount.divide(total, 8, RoundingMode.HALF_UP).toFloat() * 360f
                    drawArc(
                        color = palette[index % palette.size],
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = 44.dp.toPx()),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(money(total, currency ?: "DKK"), fontWeight = FontWeight.Bold)
                Text("Uscite", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
        slices.forEachIndexed { index, slice ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) { drawCircle(palette[index % palette.size]) }
                Spacer(Modifier.width(8.dp))
                Text(slice.name, Modifier.weight(1f))
                Text(money(slice.amount, currency ?: "DKK"))
                if (total.signum() > 0) {
                    Text("  ${slice.amount.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP)}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
internal fun CalendarScreenV2(
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    accounts: List<FinanceAccount>,
    rows: List<TransactionView>,
    projected: List<ProjectedOccurrence>,
    transfers: List<FinanceTransfer>,
) {
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    val transferIds = remember(transfers) { transfers.flatMap { listOf(it.sourceTransactionId, it.targetTransactionId) }.toSet() }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        MonthHeader(month, onMonth)
        Row(Modifier.fillMaxWidth()) {
            listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom").forEach {
                Text(it, Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cellCount = offset + month.lengthOfMonth()
        val weeks = (cellCount + 6) / 7
        repeat(weeks) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val index = week * 7 + column
                    val dayNumber = index - offset + 1
                    if (dayNumber !in 1..month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).height(90.dp))
                    } else {
                        val date = month.atDay(dayNumber)
                        CalendarDayCellV2(
                            date = date,
                            accounts = accounts,
                            rows = rows,
                            projected = projected,
                            transferIds = transferIds,
                            modifier = Modifier.weight(1f).height(90.dp),
                            onClick = { selectedDay = date },
                        )
                    }
                }
            }
        }
        Text(
            "Ogni casella mostra il patrimonio del giorno in tutte le valute. ● = ricorrenza prevista",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
    }

    selectedDay?.let { day ->
        val actual = rows.filter { localDate(it.value.occurredAt) == day }
        val future = projected.filter { it.date == day && it.materializedTransactionId == null }
        AlertDialog(
            onDismissRequest = { selectedDay = null },
            title = { Text(HubTimeFormat.localDate(day, "dd MMMM yyyy")) },
            text = {
                Column {
                    actual.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(row.title, Modifier.weight(1f))
                            Text(money(BigDecimal(row.value.amount), row.value.currency), color = amountColor(BigDecimal(row.value.amount)))
                        }
                    }
                    future.forEach { occurrence ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("↻ ${occurrence.title}", Modifier.weight(1f))
                            Text(money(BigDecimal(occurrence.amount), occurrence.currency), color = amountColor(BigDecimal(occurrence.amount)))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedDay = null }) { Text("Chiudi") } },
        )
    }
}

@Composable
private fun CalendarDayCellV2(
    date: LocalDate,
    accounts: List<FinanceAccount>,
    rows: List<TransactionView>,
    projected: List<ProjectedOccurrence>,
    transferIds: Set<Long>,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1)
    val projectionsToDate = projected.filter { !it.date.isAfter(date) }
    val wealth = FinanceCapsule.projectedTotals(accounts, rows.map { it.value }, end, projectionsToDate)
    val actualDayRows = rows.filter { localDate(it.value.occurredAt) == date && it.value.id !in transferIds }
    val projectedDay = projected.filter { it.date == date && it.materializedTransactionId == null }
    val recurrent = projectedDay.isNotEmpty()

    Surface(
        modifier = modifier.padding(1.dp).clickable(onClick = onClick),
        tonalElevation = if (date == LocalDate.now()) 3.dp else 0.dp,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(Modifier.padding(3.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(date.dayOfMonth.toString(), fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (recurrent) Text("●", color = MaterialTheme.colorScheme.primary, fontSize = 8.sp)
            }
            val dayFlow = (actualDayRows.map { it.value.currency to BigDecimal(it.value.amount) } +
                projectedDay.map { it.currency to BigDecimal(it.amount) })
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.fold(BigDecimal.ZERO, BigDecimal::add) }
            dayFlow.entries.take(2).forEach { (currency, amount) ->
                if (amount.signum() != 0) Text(compactMoney(amount, currency), color = amountColor(amount), fontSize = 8.sp, maxLines = 1)
            }
            wealth.forEach { (currency, amount) ->
                Text(compactMoney(amount, currency), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
