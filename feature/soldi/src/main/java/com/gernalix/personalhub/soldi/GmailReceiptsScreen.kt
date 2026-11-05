package com.gernalix.personalhub.soldi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

@Composable
internal fun GmailReceiptsScreen(accounts: List<FinanceAccount>, rows: List<TransactionView>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GmailReceiptStore(context) }
    val bank = remember { BankingRepository(context) }
    var catalog by remember { mutableStateOf(GmailCatalog()) }
    var snapshot by remember { mutableStateOf<BankingSnapshot?>(null) }
    var state by remember { mutableStateOf(BankingReconciliationState()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var receiptConfirm by remember { mutableStateOf<ReceiptBankMatch?>(null) }
    var removeSource by remember { mutableStateOf(false) }
    var pendingPdf by remember { mutableStateOf<GmailReceipt?>(null) }
    var appointmentEditing by remember { mutableStateOf<GmailReceipt?>(null) }
    var editing by remember { mutableStateOf<GmailReceipt?>(null) }
    var localConfirm by remember { mutableStateOf<Pair<String,String>?>(null) }
    suspend fun reload() = withContext(Dispatchers.IO) {
        Triple(store.read(), bank.cached(), bank.reconciliationState())
    }.also { (c, s, r) -> catalog = c; snapshot = s; state = r }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); reload() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: GmailApiFailure) { message = when(e.status) {
                401,403 -> "Autorizza Gmail in sola lettura. Se l'accesso è negato, verifica la configurazione Google Cloud dell'app."
                429 -> "Limite Gmail raggiunto: riprova più tardi."
                else -> "Gmail non disponibile. L’archivio precedente è conservato."
            } }
            catch (_: Exception) { message = "Operazione non completata. Controlla connessione, account e dati; l’archivio precedente è conservato." }
            finally { busy = false }
        }
    }
    suspend fun useToken(token: String) {
        val pdf = pendingPdf
        pendingPdf = null
        if (pdf != null) {
            val transport = GoogleGmailTransport(token)
            val expectedAccount = withContext(Dispatchers.IO) { store.read().account }
            require(expectedAccount != null && withContext(Dispatchers.IO) { transport.get("profile").getString("emailAddress") } == expectedAccount)
            editing = readGmailInvoicePdf(context, pdf, transport)
        }
        else { catalog = GmailReceiptClient(store, GoogleGmailTransport(token)).sync(); message = "Ricevute aggiornate. Nessun movimento contabile creato." }
    }
    fun sync(token: String) = action { useToken(token) }
    val authorize = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val token = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken }.getOrNull()
            if (!token.isNullOrBlank()) sync(token) else message = "Autorizzazione Gmail non completata."
        } else message = "Autorizzazione annullata."
    }
    LaunchedEffect(Unit) { try { reload() } catch (_: Exception) { message = "Archivio non leggibile. Nessun dato è stato sostituito." } }
    val visible = catalog.receipts.filterNot { it.messageId in catalog.excluded || (it.kind == "appointment" && catalog.appointments.any { e -> e.messageId == it.messageId }) }
    val receiptMatches = receiptBankCandidates(visible, snapshot, catalog.links, uniqueOnly = false)
    val localCandidates = snapshot?.let { bankingLocalCandidates(it, state, accounts.associateBy { a -> a.id }, rows) }.orEmpty()
    fun bankLabel(key: String): String {
        val row = snapshot?.transactions?.firstOrNull { "${it.accountId}:${it.id}" == key }
        return row?.let { "${it.description.orEmpty()} · ${it.amount.toPlainString()} ${it.currency} · ${it.date?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")).orEmpty()}" }
            ?: "Movimento non presente nella cache attuale"
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Gmail e riconciliazione", style = MaterialTheme.typography.titleLarge)
            Text("Ricevute, ordini e fatture, in sola lettura. I parser estraggono soltanto campi espliciti; i dati incompleti restano da verificare. Le associazioni non creano spese né modificano saldi.")
            catalog.account?.let { Text(it) }
            if (catalog.status == "authorization") Text("Accesso Google da autorizzare nuovamente")
            if (catalog.status == "failed") Text("Ultimo aggiornamento non riuscito: dati precedenti conservati")
            catalog.updatedAt?.let { Text("Aggiornato il ${it.atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))}") }
            Button(enabled = !busy, onClick = { pendingPdf = null; action {
                val auth = GmailAuthorization.request(context)
                if (auth.hasResolution()) authorize.launch(IntentSenderRequest.Builder(requireNotNull(auth.pendingIntent).intentSender).build())
                else useToken(auth.accessToken ?: throw GmailApiFailure(401))
            } }) { Text("Autorizza / aggiorna Gmail") }
            Text("Storico iniziale: ${catalog.days} giorni. Conserviamo solo i metadati utili nel database principale, senza corpo email o allegati.", style = MaterialTheme.typography.bodySmall)
            Row {
                listOf(30,90,365).forEach { days -> TextButton(enabled = !busy && catalog.days != days, onClick = { action {
                    withContext(Dispatchers.IO) { store.update { it.copy(days = days, history = null) } }
                } }) { Text("$days giorni") } }
            }
            Row {
                Checkbox(checked = catalog.automatic, enabled = !busy, onCheckedChange = { enabled -> action {
                    catalog = withContext(Dispatchers.IO) { store.update { it.copy(automatic = enabled) } }
                    GmailReceiptWork.schedule(context, enabled)
                } })
                Text("Aggiorna ogni giorno", Modifier.padding(top = 12.dp))
            }
            Text("Richiede consenso Google valido; Android può ritardare l’aggiornamento. Se il consenso scade, autorizza nuovamente qui.", style = MaterialTheme.typography.bodySmall)
            if (catalog.account != null) TextButton(enabled = !busy, onClick = { removeSource = true }) { Text("Rimuovi importazione Gmail da PH") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
        }
        item {
            Text("Movimenti PH ↔ banca", style = MaterialTheme.typography.titleMedium)
            Text("Associa prima i conti in Banche. Stesso conto, valuta e importo, con tolleranza di un giorno. In caso di più candidati scegli il movimento corretto.")
            if (localCandidates.isEmpty()) Text("Nessun confronto disponibile: aggiorna Banche e verifica l’associazione dei conti.")
        }
        localCandidates.forEach { (key, candidates) ->
            item(key = "bank-$key") {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(bankLabel(key))
                    val fixed = state.matchedTransactions[key]
                    if (fixed != null) {
                        Text(if (fixed in candidates) "Associazione salvata" else "Associazione da rivedere: movimento PH modificato o assente")
                        TextButton(enabled = !busy, onClick = { action { withContext(Dispatchers.IO) { bank.removeLocalMatch(key) } } }) { Text("Rimuovi associazione") }
                    } else {
                        if (candidates.isEmpty()) Text("Movimento PH mancante: rivedi il suggerimento in Banche.")
                        candidates.forEach { uuid ->
                            val local = rows.firstOrNull { it.value.uuid == uuid }
                            OutlinedButton(enabled = !busy, onClick = { localConfirm = key to uuid }) {
                                Text("Associa a ${local?.title.orEmpty()} · ${local?.value?.occurredAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.orEmpty()}")
                            }
                        }
                    }
                } }
            }
        }
        if (catalog.appointments.isNotEmpty()) item { Text("Appuntamenti da Gmail", style = MaterialTheme.typography.titleMedium) }
        items(catalog.appointments.sortedBy { it.startsAt }, key = { "event-${it.uid}" }) { event ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium)
                Text(event.dateOnly?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: event.startsAt.atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z")))
                if (event.location.isNotBlank()) Text(event.location)
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/mail/u/${Uri.encode(catalog.account.orEmpty())}/#all/${Uri.encode(event.messageId)}")))
                }) { Text("Apri invito Gmail") }
                if (event.cancelled) Text("Appuntamento annullato nella fonte: verifica gli eventuali contatori già creati")
                else TextButton(onClick = {
                    val source = com.gernalix.personalhub.contracts.database.SinceWhenSourceDescriptor(
                        entityType = "gmail_appointment", entityId = "${catalog.account}:${event.uid}", defaultCounterTitle = event.title,
                        timestampSources = listOf(com.gernalix.personalhub.contracts.database.SinceWhenTimestampSource("start", "Appuntamento", (event.dateOnly?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant() ?: event.startsAt).toEpochMilli(), true)),
                    )
                    context.startActivity(Intent(Intent.ACTION_VIEW, com.gernalix.personalhub.contracts.database.HubDeepLinkContract.sinceWhenCreateUri(source)).setPackage(context.packageName))
                }) { Text("Rivedi in Countdown / Since When") }
            } }
        }
        item { Text("Ricevute, ordini e messaggi da verificare", style = MaterialTheme.typography.titleMedium); if (visible.isEmpty()) Text("Nessuna ricevuta importata.") }
        items(visible, key = { "receipt-${it.messageId}" }) { receipt ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(receipt.merchant ?: receipt.subject, style = MaterialTheme.typography.titleMedium)
                Text(receipt.subject); Text(receipt.sender, style = MaterialTheme.typography.bodySmall)
                Text(listOfNotNull(receipt.date?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), receipt.amount?.let { "$it ${receipt.currency.orEmpty()}" }).joinToString(" · ").ifBlank { "Importo o data da verificare" })
                receipt.orderId?.let { Text("Ordine: $it") }
                receipt.tracking?.let { Text("Tracking: $it") }
                if (receipt.kind == "appointment") OutlinedButton(enabled = !busy, onClick = { appointmentEditing = receipt }) { Text("Verifica appuntamento") }
                if (!receipt.available) Text("Messaggio rimosso o escluso in Gmail: collegamento conservato per revisione")
                else if (!receipt.complete) Text("Dati incompleti: verifica la fonte prima di associare")
                val linked = catalog.links[receipt.messageId]
                if (linked != null) {
                    Text("Banca: ${bankLabel(linked)}")
                    val localId = state.matchedTransactions[linked] ?: state.acceptedSuggestions[linked]
                    Text(localId?.let { uuid -> rows.firstOrNull { it.value.uuid == uuid }?.title?.let { "Movimento PH: $it" } } ?: "Movimento PH non ancora associato o non disponibile")
                    TextButton(enabled = !busy, onClick = { action { withContext(Dispatchers.IO) { store.update { it.copy(links = it.links - receipt.messageId) } } } }) { Text("Rimuovi collegamento ricevuta") }
                } else receiptMatches.filter { it.messageId == receipt.messageId }.forEach { match ->
                    OutlinedButton(enabled = !busy, onClick = { receiptConfirm = match }) { Text("Verifica e collega: ${bankLabel(match.bankKey)}") }
                }
                if (linked == null && receipt.available && receipt.kind == "receipt") TextButton(enabled = !busy, onClick = {
                    pendingPdf = receipt
                    action {
                        val auth = GmailAuthorization.request(context)
                        if (auth.hasResolution()) authorize.launch(IntentSenderRequest.Builder(requireNotNull(auth.pendingIntent).intentSender).build())
                        else useToken(auth.accessToken ?: throw GmailApiFailure(401))
                    }
                }) { Text("Estrai campi dalla fattura PDF") }
                if (linked == null && receipt.kind != "appointment") OutlinedButton(enabled = !busy, onClick = { editing = receipt }) { Text("Verifica / completa i campi") }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/mail/u/${Uri.encode(catalog.account.orEmpty())}/#all/${Uri.encode(receipt.messageId)}")))
                }) { Text("Apri fonte Gmail") }
                TextButton(enabled = !busy && linked == null, onClick = { action { withContext(Dispatchers.IO) { store.update { it.copy(excluded = it.excluded + receipt.messageId) } } } }) { Text("Escludi ricevuta") }
            } }
        }
        if (catalog.excluded.isNotEmpty()) item { TextButton(enabled = !busy, onClick = { action { withContext(Dispatchers.IO) { store.update { it.copy(excluded = emptySet()) } } } }) { Text("Ripristina ricevute escluse") } }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (removeSource) AlertDialog(onDismissRequest = { removeSource = false }, title = { Text("Rimuovi la fonte Gmail da PH") },
        text = { Text("Rimuove i metadati importati, le esclusioni e i collegamenti delle ricevute. Ferma gli aggiornamenti. Conserva email originali e movimenti contabili. Potrai importare nuovamente o scegliere un altro account Google.") },
        confirmButton = { TextButton(onClick = { removeSource = false; action {
            withContext(Dispatchers.IO) { store.update { GmailCatalog() } }
            GmailReceiptWork.schedule(context, false)
        } }) { Text("Rimuovi da PH") } }, dismissButton = { TextButton(onClick = { removeSource = false }) { Text("Annulla") } })
    appointmentEditing?.let { receipt -> GmailAppointmentReviewDialog(receipt, onDismiss = { appointmentEditing = null }, onSave = { event ->
        appointmentEditing = null
        action { withContext(Dispatchers.IO) { store.update { current ->
            require(current.receipts.any { it.messageId == receipt.messageId && it.available })
            current.copy(appointments = current.appointments.filterNot { it.uid == event.uid } + event)
        } } }
    }) }
    editing?.let { receipt -> GmailReceiptReviewDialog(receipt,
        onDismiss = { editing = null },
        onSave = { updated -> editing = null; action { withContext(Dispatchers.IO) { store.review(updated) } } },
    ) }
    receiptConfirm?.let { match -> AlertDialog(onDismissRequest = { receiptConfirm = null }, title = { Text("Collega ricevuta al movimento") },
        text = { Text(bankLabel(match.bankKey)) }, confirmButton = { TextButton(onClick = { receiptConfirm = null; action { withContext(Dispatchers.IO) { store.link(match) { bank.cached() } } } }) { Text("Conferma") } },
        dismissButton = { TextButton(onClick = { receiptConfirm = null }) { Text("Annulla") } }) }
    localConfirm?.let { (key, uuid) -> AlertDialog(onDismissRequest = { localConfirm = null }, title = { Text("Associa movimento PH") },
        text = { Text("${bankLabel(key)}\n${rows.firstOrNull { it.value.uuid == uuid }?.title.orEmpty()}\nOgni movimento PH può essere usato una sola volta.") },
        confirmButton = { TextButton(onClick = { localConfirm = null; action { withContext(Dispatchers.IO) { bank.confirmLocalMatch(key, uuid, accounts, rows) } } }) { Text("Conferma") } },
        dismissButton = { TextButton(onClick = { localConfirm = null }) { Text("Annulla") } }) }
}

@Composable
private fun GmailReceiptReviewDialog(receipt: GmailReceipt, onDismiss: () -> Unit, onSave: (GmailReceipt) -> Unit) {
    val context = LocalContext.current
    var merchant by remember(receipt) { mutableStateOf(receipt.merchant.orEmpty()) }
    var order by remember(receipt) { mutableStateOf(receipt.orderId.orEmpty()) }
    var date by remember(receipt) { mutableStateOf(receipt.date?.toString().orEmpty()) }
    var amount by remember(receipt) { mutableStateOf(receipt.amount?.toPlainString().orEmpty()) }
    var currency by remember(receipt) { mutableStateOf(receipt.currency.orEmpty()) }
    var pasted by remember { mutableStateOf("") }
    var aiText by remember { mutableStateOf("") }
    var ai by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Verifica i dati nella fonte Gmail") }, text = {
        androidx.compose.foundation.rememberScrollState().let { scroll ->
            Column(Modifier.then(Modifier.verticalScroll(scroll))) {
                Text("Salva soltanto valori che hai verificato. Lascia vuoti i campi mancanti.")
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Venditore") })
                OutlinedTextField(order, { order = it }, label = { Text("Ordine / fattura") })
                OutlinedTextField(date, { date = it }, label = { Text("Data (AAAA-MM-GG)") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Totale") })
                OutlinedTextField(currency, { currency = it }, label = { Text("Valuta (EUR, DKK…)") })
                TextButton(onClick = { ai = !ai }) { Text("Aiuto AI opzionale") }
                if (ai) {
                    Text("Puoi condividere con un’app AI solo il testo che scegli qui. Controlla che non contenga dati che vuoi mantenere privati. Nessun invio automatico.")
                    OutlinedTextField(aiText, { aiText = it }, label = { Text("Testo della ricevuta da analizzare") })
                    OutlinedButton(enabled = aiText.isNotBlank(), onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, GmailReceiptReview.prompt(receipt, aiText)), "Scegli l’app AI"))
                    }) { Text("Condividi testo selezionato") }
                    OutlinedTextField(pasted, { pasted = it }, label = { Text("Risposta JSON dell’AI") })
                    TextButton(onClick = {
                        runCatching { GmailReceiptReview.apply(receipt, pasted) }.onSuccess {
                            merchant = it.merchant.orEmpty(); order = it.orderId.orEmpty(); date = it.date?.toString().orEmpty()
                            amount = it.amount?.toPlainString().orEmpty(); currency = it.currency.orEmpty(); error = null
                        }.onFailure { error = "Risposta non valida o riferita a un altro messaggio." }
                    }) { Text("Carica proposta da verificare") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { GmailReceiptReview.fields(receipt, merchant, order, date, amount, currency) }
            .onSuccess(onSave).onFailure { error = "Controlla data, totale positivo e valuta." }
    }) { Text("Conferma dati verificati") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
