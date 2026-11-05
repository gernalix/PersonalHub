package com.gernalix.personalhub.soldi

import android.app.Activity
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PlaySubscriptionsScreen(onRequestNotifications: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PlaySubscriptionStore(context) }
    var catalog by remember { mutableStateOf(PlayCatalog()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableStateOf(emptyList<PlayBankMatch>()) }
    var confirm by remember { mutableStateOf<PlayBankMatch?>(null) }
    var details by remember { mutableStateOf<PlaySubscription?>(null) }
    suspend fun reload() = withContext(Dispatchers.IO) { store.read() }.also { catalog = it }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); reload() }
            catch (e: CancellationException) { throw e }
            catch (_: PlayAuthorizationNeeded) { message = "Autorizza Google Play per importare o rinnovare l'accesso." }
            catch (e: PlayApiFailure) { message = when(e.statusCode) {
                401, 403 -> "Accesso Google non disponibile: verifica autorizzazione, durata del consenso e configurazione dell'app Google Cloud."
                429 -> "Google consente una nuova esportazione dopo 24 ore. Riprova più tardi."
                else -> "Google non ha completato l'esportazione. Riprova più tardi."
            } }
            catch (_: Exception) { message = "Operazione non completata. Controlla il file o la connessione; i dati precedenti sono conservati." }
            finally { busy = false }
        }
    }
    fun sync(token: String) = action {
        val previousImport = catalog.importedAt
        catalog = PlayPortabilityClient(context).sync(token)
        PlaySubscriptionWork.schedule(context, catalog)
        message = when {
            catalog.job != null -> "Google sta preparando l'archivio. Il controllo proseguirà automaticamente."
            catalog.importedAt != previousImport -> "Sottoscrizioni aggiornate."
            else -> "Una nuova esportazione è disponibile almeno 24 ore dopo la precedente."
        }
    }
    val authorize = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val auth = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data) }.getOrNull()
            val token = auth?.accessToken
            if (!token.isNullOrBlank()) sync(token) else message = "Autorizzazione Google non completata."
        } else message = "Autorizzazione annullata."
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri != null) action {
            val rows = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use(PlaySubscriptionJson::read) ?: error("File non leggibile") }
            catalog = withContext(Dispatchers.IO) { store.import(rows) }
            FinanceReminderScheduler.reschedule(context)
            message = "Importate ${rows.size} sottoscrizioni, senza creare addebiti."
        }
    }
    LaunchedEffect(Unit) { reload() }
    // Background sync may complete while this screen is visible.
    LaunchedEffect(catalog.job) {
        while (catalog.job != null) { kotlinx.coroutines.delay(15000); reload() }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Abbonamenti Google Play", style = MaterialTheme.typography.titleLarge)
            Text("Importa le sottoscrizioni per consultare rinnovi e previsioni. Gli addebiti effettivi restano nei movimenti bancari.")
            Text("Lo storico include i rinnovi disattivati. Le copie identiche sono unite; le versioni non confermate dall'ultimo file sono escluse dalle stime.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, onClick = {
                action {
                    val auth = PlayAuthorization.request(context)
                    if (auth.hasResolution()) authorize.launch(IntentSenderRequest.Builder(requireNotNull(auth.pendingIntent).intentSender).build())
                    else {
                        val token = auth.accessToken ?: throw PlayAuthorizationNeeded()
                        catalog = PlayPortabilityClient(context).sync(token)
                        PlaySubscriptionWork.schedule(context, catalog)
                    }
                }
            }) { Text("Importa / aggiorna da Google Play") }
            OutlinedButton(enabled = !busy, onClick = { importFile.launch(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/zip", "application/x-zip-compressed"))) }) { Text("Importa Google Takeout (JSON o ZIP)") }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/account/subscriptions".toUri())) }) { Text("Gestisci su Google Play") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
            Text(when (catalog.status) {
                "pending" -> "Esportazione Google in preparazione"
                "authorization" -> "Accesso da autorizzare nuovamente"
                "failed" -> "Ultimo aggiornamento non riuscito; dati precedenti conservati"
                else -> catalog.importedAt?.let { "Dati importati il ${playDate(it)}" } ?: "Nessuna importazione effettuata"
            })
        }
        item {
            Row {
                Checkbox(catalog.automatic, enabled = !busy, onCheckedChange = { enabled -> action {
                    catalog = withContext(Dispatchers.IO) { store.update { it.copy(automatic = enabled) } }
                    PlaySubscriptionWork.schedule(context, catalog)
                } })
                Text("Aggiorna automaticamente ogni 24 ore", Modifier.padding(top = 12.dp))
            }
            Text("Richiede consenso Google valido. Con accesso una tantum, autorizza una nuova esportazione. Android può ritardare le attività in background.", style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(catalog.reminders, enabled = !busy, onCheckedChange = { enabled ->
                    if (enabled) onRequestNotifications()
                    action {
                        catalog = withContext(Dispatchers.IO) { store.update { it.copy(reminders = enabled) } }
                        FinanceReminderScheduler.reschedule(context)
                    }
                })
                Text("Avvisa 3 giorni prima dei rinnovi", Modifier.padding(top = 12.dp))
            }
        }
        item {
            Text("Spesa prevista normalizzata", style = MaterialTheme.typography.titleMedium)
            val totals = catalog.forecastSubscriptions.mapNotNull { sub -> sub.currency?.let { currency -> sub.annualCost()?.let { currency to it } } }.groupBy({ it.first }, { it.second })
            if (totals.isEmpty()) Text("Prezzo e periodicità non ancora disponibili per stimare la spesa.")
            totals.toSortedMap().forEach { (currency, values) ->
                val annual = values.fold(BigDecimal.ZERO, BigDecimal::add)
                Text("${annual.divide(BigDecimal(12), 2, RoundingMode.HALF_UP)} $currency / mese · ${annual.setScale(2, RoundingMode.HALF_UP)} $currency / anno")
            }
            val unknown = catalog.subscriptions.count { it.key in catalog.unconfirmedKeys || it.annualCost() == null }
            if (unknown > 0) Text("$unknown abbonamenti esclusi dalla stima: dati incompleti, rinnovo disattivato o stato non confermato.", style = MaterialTheme.typography.bodySmall)
        }
        items(catalog.subscriptions, key = { it.key }) { sub ->
            Card(onClick = { details = sub }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(sub.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (sub.key in catalog.unconfirmedKeys) "Non confermato nell’ultima esportazione" else playState(sub.state))
                    Text(if (sub.amount != null && sub.currency != null) "${sub.amount.stripTrailingZeros().toPlainString()} ${sub.currency}" else "Prezzo non disponibile")
                    sub.renewal?.let { Text("Prossimo rinnovo: ${playDate(it)}") }
                    sub.expiration?.let { Text("Scadenza: ${playDate(it)}") }
                    Text("Addebiti associati: ${catalog.links.values.count { it == sub.key }}")
                }
            }
        }
        item {
            Text("Riconciliazione bancaria", style = MaterialTheme.typography.titleMedium)
            Text("Cerca addebiti contabilizzati Revolut/Lunar compatibili per importo, valuta, descrizione e data. Ogni associazione richiede conferma.")
            OutlinedButton(enabled = !busy, onClick = { action {
                matches = withContext(Dispatchers.IO) { playBankMatches(catalog.subscriptions, BankingRepository(context).cached(), catalog.links.keys) }
                message = if (matches.isEmpty()) "Nessun addebito univoco nella cache bancaria completa e aggiornata. Aggiorna Banche e riprova." else null
            } }) { Text("Confronta con gli addebiti bancari") }
            Text("Associazioni salvate: ${catalog.links.size}. L'associazione non crea movimenti e non modifica i saldi.", style = MaterialTheme.typography.bodySmall)
        }
        items(matches, key = { it.bankKey }) { match ->
            OutlinedButton(onClick = { confirm = match }, enabled = !busy) { Text("${match.title}: ${match.amount} ${match.currency} · ${match.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}") }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    confirm?.let { match -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Associa addebito") },
        text = { Text("Collegare ${match.amount} ${match.currency} del ${match.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} a ${match.title}?") },
        confirmButton = { TextButton(onClick = { confirm = null; action {
            withContext(Dispatchers.IO) {
                val current = store.read()
                require(match in playBankMatches(current.subscriptions, BankingRepository(context).cached(), current.links.keys))
                store.link(match)
            }
            matches = matches.filterNot { it.bankKey == match.bankKey }
        } }) { Text("Associa") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla") } }) }
    details?.let { sub -> AlertDialog(onDismissRequest = { details = null }, title = { Text(sub.title) },
        text = { Column {
            Text(playState(sub.state))
            Text(sub.period?.let { "Periodicità: ${playPeriod(it)}" } ?: "Periodicità non disponibile")
            val changes = playChangeDescriptions(sub.sourceJson)
            Text("Modifiche registrate da Google: ${changes.size}")
            changes.takeLast(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("I dettagli di pagamento e le modifiche si gestiscono su Google Play.")
        } }, confirmButton = { TextButton(onClick = { details = null }) { Text("Chiudi") } }) }
}
internal fun playChangeDescriptions(source: String): List<String> {
    val value = org.json.JSONObject(source).opt("userChangeRecord")
    val rows = when (value) {
        is org.json.JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is org.json.JSONObject -> listOf(value)
        else -> emptyList()
    }
    return rows.map { row ->
        val action = when ((row.text("type") ?: row.text("changeType"))?.uppercase()) {
            "CANCELLATION", "CANCEL", "CANCELLED", "CANCELED" -> "Cancellazione"
            "REACTIVATION", "REACTIVATE" -> "Riattivazione"
            "PAYMENT_METHOD_CHANGE", "PAYMENT_METHOD_CHANGED", "UPDATEPAYMENTMETHOD" -> "Metodo di pagamento modificato"
            "CONFIRMPRICECHANGE" -> "Modifica del prezzo confermata"
            else -> "Modifica dell'abbonamento"
        }
        val date = (row.text("timestamp") ?: row.text("changeTime") ?: row.text("date"))?.let { runCatching { playDate(Instant.parse(it)) }.getOrNull() }
        if (date == null) action else "$action · $date"
    }
}
internal fun playState(state: String) = when(state.uppercase().removePrefix("SUBSCRIPTION_STATE_")) {
    "ACTIVE" -> "Attivo"; "CANCELED", "CANCELLED" -> "Rinnovo disattivato"; "EXPIRED" -> "Scaduto"
    "PAUSED" -> "In pausa"; "ON_HOLD" -> "Pagamento sospeso"; "IN_GRACE_PERIOD" -> "Periodo di tolleranza"
    else -> "Stato non confermato"
}
private fun playDate(instant: Instant) = instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
private fun playPeriod(period: java.time.Period): String = listOfNotNull(period.years.takeIf { it != 0 }?.let { "$it anni" }, period.months.takeIf { it != 0 }?.let { "$it mesi" }, period.days.takeIf { it != 0 }?.let { "$it giorni" }).joinToString(", ")
