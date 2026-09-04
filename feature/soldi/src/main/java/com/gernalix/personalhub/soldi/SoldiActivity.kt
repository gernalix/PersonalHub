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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.onFocusChanged
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
    val accounts by capsule.accounts.collectAsState(emptyList())
    val transactions by capsule.transactions.collectAsState(emptyList())
    val products by capsule.products.collectAsState(emptyList())
    val places by capsule.places.collectAsState(emptyList())
    val tags by capsule.tagNames.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var transaction by remember { mutableStateOf<TransactionDraft?>(null) }
    var account by remember { mutableStateOf<FinanceAccount?>(null) }
    var reconcile by remember { mutableStateOf<FinanceAccount?>(null) }
    var settings by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(java.time.YearMonth.now()) }
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
    fun back() { transaction = null; product = null; account = null; reconcile = null; settings = false; error = false }
    val editing = transaction != null || product != null || account != null || reconcile != null || settings
    BackHandler(editing && !busy) { back() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(when {
                transaction != null -> R.string.transaction
                account != null -> R.string.account
                reconcile != null -> R.string.reconcile
                product != null -> R.string.product_name
                settings -> R.string.settings
                tab == 1 -> R.string.products
                tab == 2 -> R.string.accounts
                else -> R.string.transactions
            }), style = MaterialTheme.typography.headlineMedium)
            if (!editing) TextButton(onClick = { settings = true }) { Text(stringResource(R.string.settings)) }
            TextButton(enabled = !busy, onClick = { if (editing) back() else finish() }) { Text(stringResource(R.string.back)) }
        }
        if (error) Text(stringResource(R.string.operation_failed), color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            transaction != null -> TransactionEditor(transaction!!, products, places, accounts, transactions, tags, { title -> action { transaction = capsule.reuseLatestTitle(transaction!!, title) } }, { transaction = it }, busy) {
                val draft = transaction!!
                action { capsule.saveTransaction(draft); back() }
            }
            account != null -> AccountEditor(account!!, { account = it }, busy) {
                val draft = account!!; action { capsule.saveAccount(draft); back() }
            }
            reconcile != null -> ReconcileEditor(reconcile!!, transactions.map { it.value }, busy) { at, desired, title, notes ->
                val id = reconcile!!.id; action { capsule.reconcile(id,at,desired,title,notes); back(); tab = 0 }
            }
            settings -> GitSettings()
            product != null -> {
                Field(R.string.product_name, product!!.name) { product = product!!.copy(name = it) }
                Button(enabled = !busy, onClick = { val p = product!!; action { capsule.saveProduct(p.id.takeIf { it != 0L }, p.name); back() } }) { Text(stringResource(R.string.save)) }
            }
            else -> {
                Row {
                    listOf(R.string.transactions, R.string.products, R.string.accounts).forEachIndexed { index, label ->
                        TextButton(onClick = { tab = index }) { Text(stringResource(label), style = if (tab == index) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall) }
                    }
                }
                Field(R.string.search, search) { search = it }
                Row {
                    Button(onClick = { when (tab) { 0 -> transaction = TransactionDraft(accountId = accounts.firstOrNull()?.id, currency = accounts.firstOrNull()?.currency ?: "DKK"); 1 -> product = FinanceProduct(name = ""); else -> account = FinanceAccount(name = "", currency = "DKK") } }) { Text(stringResource(R.string.add)) }
                }
                if (tab == 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")))
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        0 -> {
                            if (transactions.isEmpty()) item { Text(stringResource(R.string.empty)) }
                            items(transactions.filter { row -> accounts.any { it.id == row.value.accountId && it.included } && java.time.YearMonth.from(Instant.parse(row.value.occurredAt).atZone(ZoneId.systemDefault())) == month && listOf(row.title,row.chain,row.place,row.value.notes).any { it?.contains(search,true) == true } }, key = { it.value.id }) { row ->
                                var menuOpen by remember { mutableStateOf(false) }
                                val edit = { action {
                                    transaction = TransactionDraft(row.value.id, row.title, row.value.productId != null, row.value.amount, row.value.currency,
                                        row.chain.orEmpty(), row.value.placeId, row.value.notes, capsule.tags(row.value.id).joinToString(", "), row.value.fromReceipt, row.value.occurredAt, row.value.accountId, row.value.productId)
                                } }
                                Card(onClick = edit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                                Text("${row.value.amount} ${row.value.currency}", style = MaterialTheme.typography.titleSmall)
                                            }
                                            Text(listOfNotNull(accounts.find { it.id == row.value.accountId }?.name, localDate(row.value.occurredAt)).joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            val details = listOfNotNull(row.chain, row.place, row.value.notes,
                                                if (row.value.fromReceipt) stringResource(R.string.from_receipt) else null).filter { it.isNotBlank() }
                                            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        }
                                        Box {
                                            val actions = stringResource(R.string.transaction_actions)
                                            TextButton(onClick = { menuOpen = true }, enabled = !busy, modifier = Modifier.size(48.dp).semantics { contentDescription = actions }, contentPadding = PaddingValues(0.dp)) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menuOpen = false; edit() })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuOpen = false; deletion = 0 to row.value.id })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            if (products.isEmpty()) item { Text(stringResource(R.string.empty)) }
                            items(products.filter { it.name.contains(search, true) }, key = { it.id }) { row ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text(row.name)
                                    Row {
                                        TextButton(onClick = { product = row }) { Text(stringResource(R.string.edit)) }
                                        TextButton(onClick = { transaction = TransactionDraft(title = row.name, isProduct = true, productId = row.id, accountId = accounts.firstOrNull()?.id, currency = accounts.firstOrNull()?.currency ?: "DKK") }) { Text(stringResource(R.string.purchase)) }
                                        TextButton(onClick = { deletion = 1 to row.id }) { Text(stringResource(R.string.delete)) }
                                    }
                                } }
                            }
                        }
                        else -> {
                            accounts.groupBy { it.currency }.toSortedMap().forEach { (currency, currencyAccounts) ->
                            item(key = "currency-$currency") { Text(currency, style = MaterialTheme.typography.titleLarge) }
                            items(currencyAccounts, key = { it.id }) { row ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Row { Checkbox(row.included, { value -> action { capsule.setIncluded(row.id,value) } }); Text(row.name,style = MaterialTheme.typography.titleMedium) }
                                    Text(stringResource(R.string.opening_balance) + ": ${row.openingBalance} ${row.currency}")
                                    Text(stringResource(R.string.current_balance) + ": ${FinanceCapsule.balance(row,transactions.map { it.value }).toPlainString()} ${row.currency}")
                                    Row {
                                        TextButton(onClick = { account = row }) { Text(stringResource(R.string.edit)) }
                                        TextButton(onClick = { reconcile = row }) { Text(stringResource(R.string.reconcile)) }
                                    }
                                } }
                            }
                            }
                        }
                    }
                }
                HorizontalDivider()
                val totals = FinanceCapsule.totals(accounts,transactions.map { it.value })
                if (totals.isEmpty()) Text(stringResource(R.string.empty))
                totals.toSortedMap().forEach { (currency, total) ->
                    Text(stringResource(R.string.total_owned) + ": ${total.toPlainString()} $currency", style = MaterialTheme.typography.titleLarge)
                    Text(accounts.filter { it.included && it.currency == currency }.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall)
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
private fun TransactionEditor(d: TransactionDraft, products: List<FinanceProduct>, places: List<PlaceChoice>, accounts: List<FinanceAccount>, transactions: List<TransactionView>, tags: List<String>, selectTitle: (String) -> Unit, change: (TransactionDraft) -> Unit, busy: Boolean, save: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AccountPicker(accounts,d.accountId) { change(d.copy(accountId = it.id,currency = it.currency)) } }
        item { Field(R.string.title, d.title, readOnly = d.productId != null, suggestions = transactions.filter { it.value.productId == null }.map { it.title }, selectSuggestion = selectTitle) { change(d.copy(title = it)) } }
        item { Row { Checkbox(d.isProduct, { change(d.copy(isProduct = it,productId = if(it) d.productId else null)) }); Text(stringResource(R.string.is_product)) } }
        if (d.isProduct) item { ProductPicker(products) { change(d.copy(title = it.name,productId = it.id)) } }
        item { Field(R.string.amount, d.amount) { change(d.copy(amount = it)) } }
        item { Text(stringResource(R.string.amount_help)) }
        item { Row { Checkbox(d.fromReceipt, { change(d.copy(fromReceipt = it)) }); Text(stringResource(R.string.from_receipt)) } }
        item { Field(R.string.currency, d.currency, suggestions = accounts.map { it.currency }) { change(d.copy(currency = it)) } }
        item { Field(R.string.chain, d.chain, suggestions = transactions.mapNotNull { it.chain }) { change(d.copy(chain = it)) } }
        item { PlacePicker(places, d.placeId) { change(d.copy(placeId = it)) } }
        item { Field(R.string.tags, d.tags, suggestions = tags, commaSeparated = true) { change(d.copy(tags = it)) } }
        item { Field(R.string.notes, d.notes) { change(d.copy(notes = it)) } }
        item { DateField(d.occurredAt) { change(d.copy(occurredAt = it)) } }
        item { Button(enabled = !busy, onClick = save) { Text(stringResource(R.string.save)) } }
    }
}

@Composable
private fun Field(label: Int, value: String, readOnly: Boolean = false, suggestions: List<String> = emptyList(), commaSeparated: Boolean = false, selectSuggestion: ((String) -> Unit)? = null, change: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val query = (if (commaSeparated) value.substringAfterLast(',') else value).trim()
    val options = suggestions.distinct().filter {
        it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) &&
            (!commaSeparated || value.split(',').none { selected -> selected.trim().equals(it, true) })
    }.take(4)
    Column {
        OutlinedTextField(value = value, onValueChange = change, readOnly = readOnly,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (label in listOf(R.string.amount, R.string.opening_balance, R.string.desired_balance)) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text),
            label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        if (focused && !readOnly && query.isNotEmpty()) options.forEach { suggestion ->
            TextButton(onClick = {
                (selectSuggestion ?: change)(if (commaSeparated && value.contains(',')) value.substringBeforeLast(',') + ", " + suggestion else suggestion)
            }) { Text(suggestion) }
        }
    }
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
private fun ProductPicker(products: List<FinanceProduct>, change: (FinanceProduct) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.select_product)) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.products)) },
        text = { LazyColumn { items(products, key = { it.id }) { p -> Text(p.name, Modifier.fillMaxWidth().clickable { change(p); open = false }.padding(12.dp)) } } },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
}

private fun localDate(value: String): String = DateTimeFormatter
    .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(Instant.parse(value))

@Composable
private fun DateField(value: String, change: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = Instant.parse(value).atZone(ZoneId.systemDefault())
    OutlinedButton(onClick = {
        android.app.DatePickerDialog(context, { _, year, month, day ->
            android.app.TimePickerDialog(context, { _, hour, minute ->
                change(java.time.LocalDate.of(year, month + 1, day).atTime(hour, minute)
                    .atZone(ZoneId.systemDefault()).toInstant().toString())
            }, current.hour, current.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
    }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.date_local) + ": " + localDate(value))
    }
}

@Composable
private fun AccountPicker(accounts: List<FinanceAccount>, selected: String?, change: (FinanceAccount) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.account) + ": " + (accounts.find { it.id == selected }?.name ?: stringResource(R.string.default_account))) }
    if (open) AlertDialog(onDismissRequest = { open = false },title = { Text(stringResource(R.string.accounts)) },
        text = { LazyColumn { items(accounts,key = { it.id }) { a -> Text("${a.name} (${a.currency})",Modifier.fillMaxWidth().clickable { change(a); open = false }.padding(12.dp)) } } },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.back)) } })
}

@Composable
private fun AccountEditor(value: FinanceAccount, change: (FinanceAccount) -> Unit, busy: Boolean, save: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Field(R.string.account_name,value.name) { change(value.copy(name = it)) } }
        item { Field(R.string.currency,value.currency, suggestions = java.util.Currency.getAvailableCurrencies().map { it.currencyCode }.sorted()) { change(value.copy(currency = it)) } }
        item { Field(R.string.opening_balance,value.openingBalance) { change(value.copy(openingBalance = it)) } }
        item { DateField(value.openedAt) { change(value.copy(openedAt = it)) } }
        item { Row { Checkbox(value.included,{ change(value.copy(included = it)) }); Text(stringResource(R.string.include_total)) } }
        item { Button(onClick = save,enabled = !busy) { Text(stringResource(R.string.save)) } }
    }
}

@Composable
private fun ReconcileEditor(account: FinanceAccount, rows: List<FinanceTransaction>, busy: Boolean, save: (String,String,String,String) -> Unit) {
    var at by remember { mutableStateOf(Instant.now().toString()) }
    var desired by remember { mutableStateOf("") }
    val defaultTitle = stringResource(R.string.compensation)
    var title by remember { mutableStateOf(defaultTitle) }
    var notes by remember { mutableStateOf("") }
    val balance = runCatching { FinanceCapsule.balance(account,rows,Instant.parse(at)) }.getOrNull()
    val difference = runCatching { BigDecimal(FinanceCapsule.decimal(desired)) - requireNotNull(balance) }.getOrNull()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(account.name,style = MaterialTheme.typography.titleLarge) }
        item { DateField(at) { at = it } }
        item { Text(stringResource(R.string.current_balance) + ": ${balance?.toPlainString().orEmpty()} ${account.currency}") }
        item { Field(R.string.desired_balance,desired) { desired = it } }
        item { Text(stringResource(R.string.difference) + ": ${difference?.toPlainString().orEmpty()} ${account.currency}") }
        item { Field(R.string.title,title) { title = it } }
        item { Field(R.string.notes,notes) { notes = it } }
        item { Text(stringResource(R.string.reconcile_help)) }
        item { Button(enabled = !busy && difference != null,onClick = { save(at,desired,title,notes) }) { Text(stringResource(R.string.save)) } }
    }
}

@Composable
private fun GitSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val git = remember { FinanceGit(context.applicationContext) }
    var url by remember { mutableStateOf(git.url()) }
    var status by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(push: Boolean) {
        if(running) return
        running = true; status = R.string.git_running
        scope.launch { try {
            withContext(Dispatchers.IO) { git.configure(url) }
            if(push) git.push() else git.pull()
            status = R.string.git_success
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(_: Exception) { status = R.string.git_error }
        finally { running = false } }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.git_help)) }
        item { Field(R.string.git_url,url) { url = it } }
        item { TextButton(enabled = !running,onClick = {
            scope.launch { try { withContext(Dispatchers.IO) { git.configure(url) }; status = R.string.git_configured }
                catch(_: Exception) { status = R.string.git_error } }
        }) { Text(stringResource(R.string.save)) } }

        item { Row {
            Button(enabled = !running,onClick = { run(false) }) { Text(stringResource(R.string.git_pull)) }
            TextButton(enabled = !running,onClick = { run(true) }) { Text(stringResource(R.string.git_push)) }
        } }
        item { TextButton(enabled = !running,onClick = { git.configure(""); url = ""; status = 0 }) { Text(stringResource(R.string.git_disconnect)) } }
        if(status != 0) item { Text(stringResource(status)) }
    }
}
