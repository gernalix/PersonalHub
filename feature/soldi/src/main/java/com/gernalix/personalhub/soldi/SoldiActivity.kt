package com.gernalix.personalhub.soldi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.database.capsules.soldi.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SoldiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val capsule = FinanceCapsule(applicationContext)
        setContent { MaterialTheme { Surface { SoldiScreen(capsule, ::finish) } } }
    }
}

@Composable
private fun SoldiScreen(capsule: FinanceCapsule, finish: () -> Unit) {
    val transactions by capsule.transactions.collectAsState(emptyList())
    val products by capsule.products.collectAsState(emptyList())
    val places by capsule.places.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var transaction by remember { mutableStateOf<TransactionDraft?>(null) }
    var product by remember { mutableStateOf<FinanceProduct?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = false
        scope.launch {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = true } finally { busy = false }
        }
    }
    fun back() { transaction = null; product = null; error = false }
    val editing = transaction != null || product != null
    BackHandler(editing && !busy) { back() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.soldi_title), style = MaterialTheme.typography.headlineMedium)
            TextButton(enabled = !busy, onClick = { if (editing) back() else finish() }) { Text(stringResource(R.string.back)) }
        }
        if (error) Text(stringResource(R.string.operation_failed), color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            transaction != null -> TransactionEditor(transaction!!, products, places, { transaction = it }, busy) {
                val draft = transaction!!
                action { capsule.saveTransaction(draft); back() }
            }
            product != null -> {
                Field(R.string.product_name, product!!.name) { product = product!!.copy(name = it) }
                Button(enabled = !busy, onClick = { val p = product!!; action { capsule.saveProduct(p.id.takeIf { it != 0L }, p.name); back() } }) { Text(stringResource(R.string.save)) }
            }
            else -> {
                Row {
                    listOf(R.string.transactions, R.string.products).forEachIndexed { index, label ->
                        TextButton(onClick = { tab = index }) { Text(stringResource(label), style = if (tab == index) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall) }
                    }
                }
                Field(R.string.search, search) { search = it }
                Row {
                    Button(onClick = { when (tab) { 0 -> transaction = TransactionDraft(); else -> product = FinanceProduct(name = "") } }) { Text(stringResource(R.string.add)) }
                }
                if (tab == 0) {
                    transactions.groupBy { it.value.currency }.toSortedMap().forEach { (currency, rows) ->
                        Text(stringResource(R.string.balance, rows.fold(BigDecimal.ZERO) { sum, t -> sum + BigDecimal(t.value.amount) }.toPlainString(), currency))
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        0 -> {
                            if (transactions.isEmpty()) item { Text(stringResource(R.string.empty)) }
                            items(transactions.filter { listOf(it.title, it.chain, it.place, it.value.notes).any { s -> s?.contains(search, true) == true } }, key = { it.value.id }) { row ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${row.value.amount} ${row.value.currency}")
                                    Text(listOfNotNull(row.chain, row.place).joinToString(" · "))
                                    Text(localDate(row.value.occurredAt))
                                    if (row.value.fromReceipt) Text(stringResource(R.string.from_receipt))
                                    if (row.value.notes.isNotBlank()) Text(row.value.notes)
                                    Row {
                                        TextButton(enabled = !busy, onClick = { action {
                                            transaction = TransactionDraft(row.value.id, row.title, row.value.productId != null, row.value.amount, row.value.currency,
                                                row.chain.orEmpty(), row.value.placeId, row.value.notes, capsule.tags(row.value.id).joinToString(", "), row.value.fromReceipt, row.value.occurredAt)
                                        } }) { Text(stringResource(R.string.edit)) }

                                        TextButton(enabled = !busy, onClick = { deletion = 0 to row.value.id }) { Text(stringResource(R.string.delete)) }
                                    }
                                } }
                            }
                        }
                        else -> {
                            if (products.isEmpty()) item { Text(stringResource(R.string.empty)) }
                            items(products.filter { it.name.contains(search, true) }, key = { it.id }) { row ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text(row.name)
                                    Row {
                                        TextButton(onClick = { product = row }) { Text(stringResource(R.string.edit)) }
                                        TextButton(onClick = { transaction = TransactionDraft(title = row.name, isProduct = true) }) { Text(stringResource(R.string.purchase)) }
                                        TextButton(onClick = { deletion = 1 to row.id }) { Text(stringResource(R.string.delete)) }
                                    }
                                } }
                            }
                        }
                    }
                }
            }
        }
    }
    deletion?.let { (kind, id) ->
        AlertDialog(onDismissRequest = { if (!busy) deletion = null }, title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = { TextButton(enabled = !busy, onClick = { action {
                when (kind) { 0 -> capsule.deleteTransaction(id); else -> capsule.deleteProduct(id) }
                deletion = null
            } }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deletion = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun TransactionEditor(d: TransactionDraft, products: List<FinanceProduct>, places: List<PlaceChoice>, change: (TransactionDraft) -> Unit, busy: Boolean, save: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Field(R.string.title, d.title) { change(d.copy(title = it)) } }
        item { Row { Checkbox(d.isProduct, { change(d.copy(isProduct = it)) }); Text(stringResource(R.string.is_product)) } }
        if (d.isProduct) item { ProductPicker(products) { change(d.copy(title = it)) } }
        item { Field(R.string.amount, d.amount) { change(d.copy(amount = it)) } }
        item { Text(stringResource(R.string.amount_help)) }
        item { Row { Checkbox(d.fromReceipt, { change(d.copy(fromReceipt = it)) }); Text(stringResource(R.string.from_receipt)) } }
        item { Field(R.string.currency, d.currency) { change(d.copy(currency = it)) } }
        item { Field(R.string.chain, d.chain) { change(d.copy(chain = it)) } }
        item { PlacePicker(places, d.placeId) { change(d.copy(placeId = it)) } }
        item { Field(R.string.tags, d.tags) { change(d.copy(tags = it)) } }
        item { Field(R.string.notes, d.notes) { change(d.copy(notes = it)) } }
        item { DateField(d.occurredAt) { change(d.copy(occurredAt = it)) } }
        item { Button(enabled = !busy, onClick = save) { Text(stringResource(R.string.save)) } }
    }
}

@Composable
private fun Field(label: Int, value: String, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PlacePicker(places: List<PlaceChoice>, selected: String?, change: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.place) + ": " + (places.find { it.id == selected }?.name ?: stringResource(R.string.none))) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.place)) },
        text = { LazyColumn { item { Text(stringResource(R.string.none), Modifier.fillMaxWidth().clickable { change(null); open = false }.padding(12.dp)) }
            items(places, key = { it.id }) { p -> Text(p.name, Modifier.fillMaxWidth().clickable { change(p.id); open = false }.padding(12.dp)) } } },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ProductPicker(products: List<FinanceProduct>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.select_product)) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.products)) },
        text = { LazyColumn { items(products, key = { it.id }) { p -> Text(p.name, Modifier.fillMaxWidth().clickable { change(p.name); open = false }.padding(12.dp)) } } },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
}

private fun localDate(value: String): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))

@Composable
private fun DateField(value: String, change: (String) -> Unit) {
    var input by remember { mutableStateOf(runCatching {
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    }.getOrDefault(value)) }
    Field(R.string.date_local, input) {
        input = it
        change(runCatching { java.time.OffsetDateTime.parse(it).toInstant().toString() }.getOrDefault(it))
    }
}
