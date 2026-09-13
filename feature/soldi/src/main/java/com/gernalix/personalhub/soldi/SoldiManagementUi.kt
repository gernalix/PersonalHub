package com.gernalix.personalhub.soldi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.database.capsules.soldi.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun AccountsScreenV2(
    accounts: List<FinanceAccount>,
    rows: List<FinanceTransaction>,
    onEdit: (FinanceAccount) -> Unit,
    onIncluded: (FinanceAccount, Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Conti", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        accounts.groupBy { it.currency }.toSortedMap().forEach { (currency, group) ->
            item(key = "currency:$currency") { Text(currency, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            items(group, key = { it.id }) { account ->
                Card(Modifier.fillMaxWidth().clickable { onEdit(account) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(account.included, { onIncluded(account, it) })
                        Column(Modifier.weight(1f)) {
                            Text(account.name, fontWeight = FontWeight.SemiBold)
                            Text("Saldo attuale", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(FinanceCapsule.balance(account, rows), currency), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
internal fun CategoriesScreenV2(rows: List<TransactionView>) {
    val categories = rows.filter { it.value.category.isNotBlank() }
        .groupBy { it.value.category }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    var selected by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Categorie", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Le categorie sono gerarchiche come testo (es. Spesa › Supermercato); i tag restano indipendenti e multipli.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        categories.forEach { (category, categoryRows) ->
            item(key = category) {
                Card(Modifier.fillMaxWidth().clickable { selected = category }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(category, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${categoryRows.size} movimenti", style = MaterialTheme.typography.bodySmall)
                        }
                        categoryRows.groupBy { it.value.currency }.toSortedMap().forEach { (currency, currencyRows) ->
                            val total = currencyRows.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row.value.amount) }
                            Text(money(total, currency), color = amountColor(total), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (categories.isEmpty()) item { Text("Nessuna categoria ancora usata.") }
        item { Spacer(Modifier.height(80.dp)) }
    }

    selected?.let { category ->
        val categoryRows = categories[category].orEmpty().sortedByDescending { it.value.occurredAt }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(category) },
            text = {
                LazyColumn {
                    items(categoryRows, key = { it.value.id }) { row ->
                        ListItem(
                            headlineContent = { Text(row.title) },
                            supportingContent = { Text(formatDateTime(row.value.occurredAt)) },
                            trailingContent = { Text(money(BigDecimal(row.value.amount), row.value.currency), color = amountColor(BigDecimal(row.value.amount))) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Chiudi") } },
        )
    }
}

private enum class RecurrenceFilter { ACTIVE, EXPIRED, ALL }

@Composable
internal fun RecurrencesScreenV2(
    recurrences: List<FinanceRecurrence>,
    nextOccurrence: suspend (FinanceRecurrence) -> LocalDate?,
    onEdit: (FinanceRecurrence) -> Unit,
    onToggle: (FinanceRecurrence, Boolean) -> Unit,
    onChangeNextAmount: (FinanceRecurrence, LocalDate, String, String?, RecurrenceEditScope) -> Unit,
) {
    var filter by remember { mutableStateOf(RecurrenceFilter.ACTIVE) }
    var amountEdit by remember { mutableStateOf<Pair<FinanceRecurrence, LocalDate>?>(null) }
    val nextDates = remember { mutableStateMapOf<String, LocalDate?>() }
    val today = LocalDate.now()

    LaunchedEffect(recurrences.map { it.id to it.updatedAt }) {
        recurrences.forEach { nextDates[it.id] = nextOccurrence(it) }
    }

    val shown = recurrences.filter { rule ->
        val ended = rule.endDate?.let(LocalDate::parse)?.isBefore(today) == true
        when (filter) {
            RecurrenceFilter.ACTIVE -> rule.enabled && !ended
            RecurrenceFilter.EXPIRED -> ended || !rule.enabled
            RecurrenceFilter.ALL -> true
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Ricorrenze", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    RecurrenceFilter.ACTIVE to "Attive",
                    RecurrenceFilter.EXPIRED to "Scadute",
                    RecurrenceFilter.ALL to "Tutte",
                ).forEach { (value, label) ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) }, modifier = Modifier.weight(1f))
                }
            }
        }
        items(shown, key = { it.id }) { rule ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(rule) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(rule.title.firstOrNull()?.uppercase() ?: "€", fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rule.title, fontWeight = FontWeight.SemiBold)
                        val amountText = if (rule.kind == "TRANSFER" && rule.targetAmount != null) {
                            "${money(BigDecimal(rule.amount), rule.currency)} → ${rule.targetAmount}"
                        } else money(BigDecimal(rule.amount), rule.currency)
                        Text("$amountText · ${recurrenceScheduleLabel(rule)}", style = MaterialTheme.typography.bodySmall)
                        nextDates[rule.id]?.let { date ->
                            Text("Prossima: ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { amountEdit = rule to date }, contentPadding = PaddingValues(0.dp)) { Text("Modifica importo prossima") }
                        }
                    }
                    Switch(rule.enabled, { onToggle(rule, it) })
                }
            }
        }
        if (shown.isEmpty()) item { Text("Nessuna ricorrenza in questa sezione.") }
        item { Spacer(Modifier.height(80.dp)) }
    }

    amountEdit?.let { (rule, date) ->
        RecurrenceAmountDialogV2(
            rule = rule,
            date = date,
            onDismiss = { amountEdit = null },
            onSave = { amount, targetAmount, scope ->
                onChangeNextAmount(rule, date, amount, targetAmount, scope)
                amountEdit = null
            },
        )
    }
}

@Composable
private fun RecurrenceAmountDialogV2(
    rule: FinanceRecurrence,
    date: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, String?, RecurrenceEditScope) -> Unit,
) {
    var amount by remember(rule.id, date) { mutableStateOf(BigDecimal(rule.amount).abs().stripTrailingZeros().toPlainString()) }
    var targetAmount by remember(rule.id, date) { mutableStateOf(rule.targetAmount.orEmpty()) }
    var scope by remember { mutableStateOf(RecurrenceEditScope.ONLY_THIS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica importo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Occorrenza ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}")
                OutlinedTextField(amount, { amount = it }, label = { Text(if (rule.kind == "TRANSFER") "Importo da prelevare" else "Importo") }, singleLine = true)
                if (rule.kind == "TRANSFER") {
                    OutlinedTextField(targetAmount, { targetAmount = it }, label = { Text("Importo da ricevere") }, singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(scope == RecurrenceEditScope.ONLY_THIS, { scope = RecurrenceEditScope.ONLY_THIS })
                    Text("Solo questa")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(scope == RecurrenceEditScope.THIS_AND_FOLLOWING, { scope = RecurrenceEditScope.THIS_AND_FOLLOWING })
                    Text("Questa e tutte le successive")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(amount, targetAmount.takeIf { rule.kind == "TRANSFER" }, scope) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
