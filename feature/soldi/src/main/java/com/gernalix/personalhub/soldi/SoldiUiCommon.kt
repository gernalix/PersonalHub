package com.gernalix.personalhub.soldi

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.PersonChoice
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SoldiTopBar(
    tab: SoldiTab,
    onTab: (SoldiTab) -> Unit,
    onClose: () -> Unit,
    onViewOptions: () -> Unit,
    onMore: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹", fontSize = 24.sp) }
            Text("Soldi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onViewOptions) { Text("◉") }
            TextButton(onClick = onMore) { Text("⋮", fontSize = 20.sp) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(
                SoldiTab.TRANSACTIONS to "Transazioni",
                SoldiTab.OVERVIEW to "Overview",
                SoldiTab.STATISTICS to "Statistiche",
                SoldiTab.CHARTS to "Grafici",
                SoldiTab.CALENDAR to "Calendario",
            ).forEach { (value, label) ->
                TextButton(
                    onClick = { onTab(value) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 1.dp),
                ) {
                    Text(
                        label,
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = if (tab == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun SoldiBottomBar(selected: BottomDestination, onSelect: (BottomDestination) -> Unit) {
    NavigationBar {
        listOf(
            BottomDestination.HOME to "Home",
            BottomDestination.ACCOUNTS to "Conti",
            BottomDestination.CATEGORIES to "Categorie",
            BottomDestination.RECURRENCES to "Ricorrenze",
            BottomDestination.MORE to "Altro",
        ).forEach { (destination, label) ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = {
                    Text(
                        when (destination) {
                            BottomDestination.HOME -> "⌂"
                            BottomDestination.ACCOUNTS -> "▣"
                            BottomDestination.CATEGORIES -> "◇"
                            BottomDestination.RECURRENCES -> "↻"
                            BottomDestination.MORE -> "≡"
                        },
                    )
                },
                label = { Text(label, fontSize = 10.sp) },
            )
        }
    }
}

@Composable
internal fun EditorHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹", fontSize = 24.sp) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider()
}

@Composable
internal fun MonthHeader(month: YearMonth, onMonth: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onMonth(month.minusMonths(1)) }) { Text("‹", fontSize = 22.sp) }
        Text(
            month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = { onMonth(month.plusMonths(1)) }) { Text("›", fontSize = 22.sp) }
    }
}

@Composable
internal fun MonthTitle(month: YearMonth) {
    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
internal fun ViewOptionsDialog(value: ViewOptions, onChange: (ViewOptions) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Opzioni di visualizzazione") },
        text = {
            Column {
                CheckOption("Mostra saldo giornaliero", "Saldo dopo le operazioni di quel giorno", value.dailyBalance) { onChange(value.copy(dailyBalance = it)) }
                CheckOption("Nascondi transazioni future", "Non mostra le transazioni future nella lista", value.hideFuture) { onChange(value.copy(hideFuture = it)) }
                CheckOption("Ignora trasferimenti nel cash flow", "Spostare denaro tra i tuoi conti non è entrata né uscita", value.ignoreTransfers) { onChange(value.copy(ignoreTransfers = it)) }
                CheckOption("Mostra importi nella valuta del conto", "Ogni importo resta nella valuta nativa del conto", value.showAccountCurrency) { onChange(value.copy(showAccountCurrency = it)) }
                CheckOption("Raggruppa per giorno", "Mostra una card giornaliera con saldo progressivo", value.groupByDay) { onChange(value.copy(groupByDay = it)) }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("OK") } },
    )
}

@Composable
private fun CheckOption(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked, onChecked)
        Column(Modifier.padding(top = 4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AccountField(
    accounts: List<FinanceAccount>,
    selectedId: String?,
    label: String = "Conto",
    onSelect: (FinanceAccount) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = accounts.find { it.id == selectedId }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(selected?.let { "${it.name} (${it.currency})" } ?: "Seleziona")
        }
        Text("⌄")
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = {
            LazyColumn {
                items(accounts, key = { it.id }) { account ->
                    ListItem(
                        headlineContent = { Text(account.name) },
                        supportingContent = { Text(account.currency) },
                        modifier = Modifier.clickable { onSelect(account); open = false },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Chiudi") } },
    )
}

@Composable
internal fun CurrencyField(value: String, currencies: List<String>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.widthIn(min = 100.dp)) { Text(value.ifBlank { "Valuta" }) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Valuta") },
        text = {
            LazyColumn {
                items((currencies + listOf("DKK", "EUR", "USD", "GBP")).distinct().sorted()) { code ->
                    Text(code, Modifier.fillMaxWidth().clickable { onChange(code); open = false }.padding(12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Chiudi") } },
    )
}

@Composable
internal fun PersonAutocomplete(people: List<PersonChoice>, personId: Long?, onSelect: (Long?) -> Unit) {
    val selected = people.find { it.id == personId }
    var query by remember(personId, selected?.name) { mutableStateOf(selected?.name.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (selected?.name?.equals(it, ignoreCase = true) != true) onSelect(null)
            },
            label = { Text("Persona") },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            singleLine = true,
        )
        if (focused && query.isNotBlank()) {
            people.filter { it.name.contains(query, ignoreCase = true) && it.id != personId }.take(5).forEach { person ->
                ListItem(
                    headlineContent = { Text(person.name) },
                    leadingContent = {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(30.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(person.name.firstOrNull()?.uppercase() ?: "P") }
                        }
                    },
                    modifier = Modifier.clickable {
                        query = person.name
                        onSelect(person.id)
                        focused = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SuggestionField(
    label: String,
    value: String,
    suggestions: List<String>,
    multiple: Boolean = false,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val query = (if (multiple) value.substringAfterLast(',') else value).trim()
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            singleLine = true,
        )
        if (focused && query.isNotBlank()) {
            suggestions.asSequence()
                .filter { it.contains(query, ignoreCase = true) }
                .filterNot { candidate -> multiple && value.split(',').any { it.trim().equals(candidate, ignoreCase = true) } }
                .distinct()
                .take(4)
                .forEach { suggestion ->
                    TextButton(onClick = {
                        onChange(
                            if (multiple && value.contains(',')) value.substringBeforeLast(',') + ", " + suggestion
                            else suggestion,
                        )
                    }) { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
        }
    }
}

@Composable
internal fun DateTimeButton(label: String, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { pickDateTime(context, Instant.parse(value), onChange) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(formatDateTime(value))
        }
    }
}

@Composable
internal fun LocalDateButton(label: String, value: String?, allowClear: Boolean = false, onChange: (String?) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = {
                val initial = value?.let(LocalDate::parse) ?: LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d).toString()) },
                    initial.year,
                    initial.monthValue - 1,
                    initial.dayOfMonth,
                ).show()
            },
            modifier = Modifier.weight(1f),
        ) { Text("$label: ${value ?: "—"}") }
        if (allowClear && value != null) TextButton(onClick = { onChange(null) }) { Text("×") }
    }
}

internal fun formatDateTime(iso: String): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
internal fun formatDateTime(epochMs: Long): String = formatDateTime(Instant.ofEpochMilli(epochMs).toString())

internal fun pickDateTime(context: Context, initial: Instant, onResult: (String) -> Unit) {
    val current = initial.atZone(ZoneId.systemDefault())
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onResult(
                        LocalDate.of(year, month + 1, day)
                            .atTime(hour, minute)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toString(),
                    )
                },
                current.hour,
                current.minute,
                android.text.format.DateFormat.is24HourFormat(context),
            ).show()
        },
        current.year,
        current.monthValue - 1,
        current.dayOfMonth,
    ).show()
}
