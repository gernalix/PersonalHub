package com.gernalix.personalhub.soldi

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.database.capsules.soldi.*
import com.gernalix.personalhub.soldi.receipt.ReceiptOcrProcessor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

@Composable
internal fun TransactionEditorV2(
    state: SoldiEditor.Transaction,
    accounts: List<FinanceAccount>,
    people: List<PersonChoice>,
    categories: List<String>,
    tags: List<String>,
    existingAttachments: List<FinanceAttachment>,
    onBack: () -> Unit,
    onChange: (SoldiEditor.Transaction) -> Unit,
    onSwitchToTransfer: () -> Unit,
    onRequestNotifications: () -> Unit,
    onDeleteAttachment: (FinanceAttachment) -> Unit,
    onSave: (TransactionDraft, RecurrenceDraft?, List<AttachmentDraft>, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val draftKey = state.draft.id?.toString() ?: state.draft.occurredAt
    var pendingAttachments by remember(draftKey) { mutableStateOf<List<AttachmentDraft>>(emptyList()) }
    var linkText by remember(draftKey) { mutableStateOf("") }
    var recurring by remember(draftKey) { mutableStateOf(false) }
    var lastBusinessDay by remember(draftKey) { mutableStateOf(false) }
    var recurrenceDay by remember(draftKey) { mutableStateOf(localDate(state.draft.occurredAt).dayOfMonth) }
    var recurrenceReminderDays by remember(draftKey) { mutableStateOf<Int?>(null) }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pendingAttachments = pendingAttachments + AttachmentDraft(
                kind = "URI",
                uri = uri.toString(),
                title = uri.lastPathSegment.orEmpty(),
                mimeType = context.contentResolver.getType(uri),
            )
        }
    }

    fun recurrenceDraft(): RecurrenceDraft? {
        if (!recurring) return null
        val accountId = state.draft.accountId ?: return null
        return RecurrenceDraft(
            title = state.draft.title,
            amount = state.draft.amount,
            currency = state.draft.currency,
            accountId = accountId,
            personId = state.draft.personId,
            chain = state.draft.chain,
            placeId = state.draft.placeId,
            notes = state.draft.notes,
            tags = state.draft.tags,
            dayOfMonth = if (lastBusinessDay) null else recurrenceDay,
            lastBusinessDay = lastBusinessDay,
            startDate = localDate(state.draft.occurredAt).toString(),
            reminderDaysBefore = recurrenceReminderDays,
            kind = if (state.kind == EntryKind.EXPENSE) "EXPENSE" else "INCOME",
            category = state.draft.category,
        )
    }

    val valid = state.draft.title.isNotBlank() && state.draft.amount.isNotBlank() && state.draft.accountId != null

    Column(Modifier.fillMaxSize()) {
        EditorHeader(if (state.draft.id == null) "Aggiungi transazione" else "Modifica transazione", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = state.kind == EntryKind.EXPENSE,
                onClick = { onChange(state.copy(kind = EntryKind.EXPENSE)) },
                label = { Text("Spesa") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = state.kind == EntryKind.INCOME,
                onClick = { onChange(state.copy(kind = EntryKind.INCOME)) },
                label = { Text("Entrata") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(selected = false, onClick = onSwitchToTransfer, label = { Text("Trasferimento") }, modifier = Modifier.weight(1f))
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(state.draft.title, { onChange(state.copy(draft = state.draft.copy(title = it))) }, label = { Text("Titolo") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(state.draft.amount.removePrefix("-"), { onChange(state.copy(draft = state.draft.copy(amount = it))) }, label = { Text("Importo") }, modifier = Modifier.weight(1f), singleLine = true)
                    CurrencyField(state.draft.currency, accounts.map { it.currency }.distinct()) { onChange(state.copy(draft = state.draft.copy(currency = it))) }
                }
            }
            item { DateTimeButton("Data", state.draft.occurredAt) { onChange(state.copy(draft = state.draft.copy(occurredAt = it))) } }
            item { AccountField(accounts, state.draft.accountId) { account -> onChange(state.copy(draft = state.draft.copy(accountId = account.id, currency = account.currency))) } }
            item { SuggestionField("Categoria", state.draft.category, categories) { onChange(state.copy(draft = state.draft.copy(category = it))) } }
            item { PersonAutocomplete(people, state.draft.personId) { onChange(state.copy(draft = state.draft.copy(personId = it))) } }
            item { SuggestionField("Tag (multipli)", state.draft.tags, tags, multiple = true) { onChange(state.copy(draft = state.draft.copy(tags = it))) } }
            item { OutlinedTextField(state.draft.chain, { onChange(state.copy(draft = state.draft.copy(chain = it))) }, label = { Text("Esercente / catena") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { OutlinedTextField(state.draft.notes, { onChange(state.copy(draft = state.draft.copy(notes = it))) }, label = { Text("Nota") }, modifier = Modifier.fillMaxWidth(), minLines = 2) }

            if (state.draft.recurrenceId == null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(recurring, { recurring = it })
                                Spacer(Modifier.width(8.dp))
                                Text("Transazione ricorrente", fontWeight = FontWeight.SemiBold)
                            }
                            if (recurring) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(!lastBusinessDay, { lastBusinessDay = false })
                                    Text("Giorno del mese")
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(recurrenceDay.toString(), { recurrenceDay = it.toIntOrNull()?.coerceIn(1, 31) ?: recurrenceDay }, modifier = Modifier.width(76.dp), singleLine = true)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(lastBusinessDay, { lastBusinessDay = true })
                                    Text("Ultimo giorno lavorativo")
                                }
                                OutlinedTextField(recurrenceReminderDays?.toString().orEmpty(), { recurrenceReminderDays = it.toIntOrNull()?.coerceAtLeast(0) }, label = { Text("Promemoria ricorrenza: giorni prima") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                }
            } else {
                item { Text("Questa è un'occorrenza di una ricorrenza. Le modifiche alla serie si fanno dalla schermata Ricorrenze.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Promemoria", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            if (state.draft.reminderAt == null) {
                                TextButton(onClick = {
                                    onRequestNotifications()
                                    pickDateTime(context, Instant.now().plusSeconds(3600)) { onChange(state.copy(draft = state.draft.copy(reminderAt = it))) }
                                }) { Text("Aggiungi") }
                            } else {
                                TextButton(onClick = { onChange(state.copy(draft = state.draft.copy(reminderAt = null))) }) { Text("Rimuovi") }
                            }
                        }
                        state.draft.reminderAt?.let { Text(formatDateTime(it), style = MaterialTheme.typography.bodySmall) }
                        Text("Vale anche per una singola transazione futura non ricorrente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                AttachmentEditor(
                    existingAttachments,
                    pendingAttachments,
                    linkText,
                    { linkText = it },
                    { documentLauncher.launch(arrayOf("image/*", "application/pdf")) },
                    {
                        val link = linkText.trim()
                        if (link.isNotEmpty()) {
                            pendingAttachments = pendingAttachments + AttachmentDraft("URL", link, link)
                            linkText = ""
                        }
                    },
                    onDeleteAttachment,
                    { index -> pendingAttachments = pendingAttachments.toMutableList().also { it.removeAt(index) } },
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = valid, onClick = { onSave(state.draft, recurrenceDraft(), pendingAttachments, true) }, modifier = Modifier.weight(1f)) { Text("Salva e nuova") }
            Button(enabled = valid, onClick = { onSave(state.draft, recurrenceDraft(), pendingAttachments, false) }, modifier = Modifier.weight(1f)) { Text("Salva") }
        }
    }
}

@Composable
internal fun TransferEditorV2(
    state: TransferUiState,
    accounts: List<FinanceAccount>,
    people: List<PersonChoice>,
    categories: List<String>,
    tags: List<String>,
    existingAttachments: List<FinanceAttachment>,
    onBack: () -> Unit,
    onChange: (TransferUiState) -> Unit,
    onRequestNotifications: () -> Unit,
    onDeleteAttachment: (FinanceAttachment) -> Unit,
    onSave: (TransferUiState, RecurrenceDraft?, List<AttachmentDraft>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ocr = remember { ReceiptOcrProcessor() }
    val stateKey = state.transferId ?: state.occurredAt
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrError by remember { mutableStateOf<String?>(null) }
    var pendingAttachments by remember(stateKey) { mutableStateOf<List<AttachmentDraft>>(emptyList()) }
    var linkText by remember(stateKey) { mutableStateOf("") }
    var recurring by remember(stateKey) { mutableStateOf(false) }
    var lastBusinessDay by remember(stateKey) { mutableStateOf(false) }
    var recurrenceDay by remember(stateKey) { mutableStateOf(localDate(state.occurredAt).dayOfMonth) }
    var recurrenceReminderDays by remember(stateKey) { mutableStateOf<Int?>(null) }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pendingAttachments = pendingAttachments + AttachmentDraft("URI", uri.toString(), uri.lastPathSegment.orEmpty(), context.contentResolver.getType(uri))
        }
    }

    val screenshotLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            ocrBusy = true
            ocrError = null
            runCatching { RevolutScreenshotParser.parse(ocr.recognize(context, uri), accounts) }
                .onSuccess { candidate ->
                    if (candidate == null) ocrError = "Non riconosco un trasferimento Revolut completo."
                    else onChange(state.copy(
                        crossCurrency = candidate.sourceCurrency != candidate.targetCurrency,
                        title = "Cambio valuta",
                        sourceAccountId = candidate.sourceAccountId ?: state.sourceAccountId,
                        targetAccountId = candidate.targetAccountId ?: state.targetAccountId,
                        sourceAmount = candidate.sourceAmount,
                        targetAmount = candidate.targetAmount,
                        quotedRate = candidate.quotedRate.orEmpty(),
                        feeAmount = candidate.feeAmount.orEmpty(),
                        feeCurrency = candidate.feeCurrency.orEmpty(),
                    ))
                }
                .onFailure { ocrError = it.message ?: "OCR non riuscito" }
            ocrBusy = false
        }
    }

    val source = accounts.find { it.id == state.sourceAccountId }
    val target = accounts.find { it.id == state.targetAccountId }
    val effectiveRate = runCatching {
        if (state.sourceAmount.isBlank() || state.targetAmount.isBlank()) null else FinanceCapsule.effectiveRate(state.sourceAmount, state.targetAmount).toPlainString()
    }.getOrNull()

    fun recurrenceDraft(): RecurrenceDraft? {
        if (!recurring) return null
        val sourceAccount = source ?: return null
        val targetAccount = target ?: return null
        return RecurrenceDraft(
            title = state.title,
            amount = state.sourceAmount,
            currency = sourceAccount.currency,
            accountId = sourceAccount.id,
            personId = state.personId,
            notes = state.notes,
            tags = state.tags,
            dayOfMonth = if (lastBusinessDay) null else recurrenceDay,
            lastBusinessDay = lastBusinessDay,
            startDate = localDate(state.occurredAt).toString(),
            reminderDaysBefore = recurrenceReminderDays,
            kind = "TRANSFER",
            targetAccountId = targetAccount.id,
            targetAmount = state.targetAmount,
            quotedRate = state.quotedRate.trim().takeIf { it.isNotEmpty() },
            feeAmount = state.feeAmount.trim().takeIf { it.isNotEmpty() },
            feeCurrency = state.feeCurrency.trim().takeIf { it.isNotEmpty() },
            category = state.category,
        )
    }

    val valid = source != null && target != null && source.id != target.id && state.sourceAmount.isNotBlank() && state.targetAmount.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        EditorHeader(if (state.transferId == null) "Trasferimento" else "Modifica trasferimento", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = !state.crossCurrency,
                onClick = {
                    val sameCurrencyTarget = source?.let { s -> accounts.firstOrNull { it.id != s.id && it.currency == s.currency } }
                    onChange(state.copy(
                        crossCurrency = false,
                        title = if (state.title == "Cambio valuta") "Trasferimento" else state.title,
                        targetAccountId = sameCurrencyTarget?.id,
                        targetAmount = state.sourceAmount,
                        quotedRate = "",
                        feeAmount = "",
                        feeCurrency = source?.currency.orEmpty(),
                    ))
                },
                label = { Text("Semplice") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(selected = state.crossCurrency, onClick = { onChange(state.copy(crossCurrency = true, title = if (state.title == "Trasferimento") "Cambio valuta" else state.title)) }, label = { Text("Trans-valuta") }, modifier = Modifier.weight(1f))
        }

        LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.crossCurrency) {
                item {
                    OutlinedButton(onClick = { screenshotLauncher.launch("image/*") }, enabled = !ocrBusy, modifier = Modifier.fillMaxWidth()) { Text(if (ocrBusy) "Lettura screenshot…" else "▣  Importa da screenshot Revolut") }
                    ocrError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { OutlinedTextField(state.title, { onChange(state.copy(title = it)) }, label = { Text("Titolo") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { AccountField(accounts, state.sourceAccountId, "Da conto") { onChange(state.copy(sourceAccountId = it.id, feeCurrency = state.feeCurrency.ifBlank { it.currency })) } }
            item { OutlinedTextField(state.sourceAmount, { onChange(state.copy(sourceAmount = it, targetAmount = if (state.crossCurrency) state.targetAmount else it)) }, label = { Text("Importo da prelevare ${source?.currency.orEmpty()}") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { Text("⇅", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.headlineSmall) }
            item {
                val targetChoices = if (state.crossCurrency || source == null) accounts else accounts.filter { it.id != source.id && it.currency == source.currency }
                AccountField(targetChoices, state.targetAccountId, "A conto") { onChange(state.copy(targetAccountId = it.id, targetAmount = if (state.crossCurrency) state.targetAmount else state.sourceAmount)) }
            }
            item { OutlinedTextField(state.targetAmount, { onChange(state.copy(targetAmount = it)) }, label = { Text("Importo da ricevere ${target?.currency.orEmpty()}") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }

            if (state.crossCurrency) {
                item { OutlinedTextField(state.quotedRate, { onChange(state.copy(quotedRate = it)) }, label = { Text("Tasso di cambio quotato (opzionale)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(state.feeAmount, { onChange(state.copy(feeAmount = it)) }, label = { Text("Commissione") }, modifier = Modifier.weight(1f), singleLine = true)
                        CurrencyField(state.feeCurrency.ifBlank { target?.currency ?: source?.currency ?: "DKK" }, accounts.map { it.currency }.distinct()) { onChange(state.copy(feeCurrency = it)) }
                    }
                }
                effectiveRate?.let { rate -> item { Text("Tasso effettivo: $rate ${target?.currency.orEmpty()} per 1 ${source?.currency.orEmpty()} (calcolato dagli importi)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }

            item { SuggestionField("Categoria", state.category, categories) { onChange(state.copy(category = it)) } }
            item { PersonAutocomplete(people, state.personId) { onChange(state.copy(personId = it)) } }
            item { SuggestionField("Tag (multipli)", state.tags, tags, multiple = true) { onChange(state.copy(tags = it)) } }
            item { DateTimeButton("Data", state.occurredAt) { onChange(state.copy(occurredAt = it)) } }
            item { OutlinedTextField(state.notes, { onChange(state.copy(notes = it)) }, label = { Text("Nota") }, modifier = Modifier.fillMaxWidth(), minLines = 2) }

            if (state.transferId == null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(recurring, { recurring = it }); Spacer(Modifier.width(8.dp)); Text("Trasferimento ricorrente", fontWeight = FontWeight.SemiBold) }
                            if (recurring) {
                                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!lastBusinessDay, { lastBusinessDay = false }); Text("Giorno del mese"); Spacer(Modifier.width(8.dp)); OutlinedTextField(recurrenceDay.toString(), { recurrenceDay = it.toIntOrNull()?.coerceIn(1, 31) ?: recurrenceDay }, modifier = Modifier.width(76.dp), singleLine = true) }
                                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(lastBusinessDay, { lastBusinessDay = true }); Text("Ultimo giorno lavorativo") }
                                OutlinedTextField(recurrenceReminderDays?.toString().orEmpty(), { recurrenceReminderDays = it.toIntOrNull()?.coerceAtLeast(0) }, label = { Text("Promemoria ricorrenza: giorni prima") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Promemoria", fontWeight = FontWeight.SemiBold); state.reminderAt?.let { Text(formatDateTime(it), style = MaterialTheme.typography.bodySmall) } }
                        TextButton(onClick = {
                            if (state.reminderAt == null) { onRequestNotifications(); pickDateTime(context, Instant.now().plusSeconds(3600)) { onChange(state.copy(reminderAt = it)) } }
                            else onChange(state.copy(reminderAt = null))
                        }) { Text(if (state.reminderAt == null) "Aggiungi" else "Rimuovi") }
                    }
                }
            }

            item {
                AttachmentEditor(
                    existingAttachments,
                    pendingAttachments,
                    linkText,
                    { linkText = it },
                    { documentLauncher.launch(arrayOf("image/*", "application/pdf")) },
                    {
                        val link = linkText.trim()
                        if (link.isNotEmpty()) { pendingAttachments = pendingAttachments + AttachmentDraft("URL", link, link); linkText = "" }
                    },
                    onDeleteAttachment,
                    { index -> pendingAttachments = pendingAttachments.toMutableList().also { it.removeAt(index) } },
                )
            }
        }

        Button(onClick = { onSave(state, recurrenceDraft(), pendingAttachments) }, enabled = valid, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Salva") }
    }
}

@Composable
internal fun RecurrenceEditorV2(
    value: RecurrenceDraft,
    accounts: List<FinanceAccount>,
    people: List<PersonChoice>,
    categories: List<String>,
    tags: List<String>,
    onChange: (RecurrenceDraft) -> Unit,
    onBack: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSave: () -> Unit,
) {
    val source = accounts.find { it.id == value.accountId }
    val target = accounts.find { it.id == value.targetAccountId }
    val transfer = value.kind == "TRANSFER"
    val crossCurrency = transfer && source != null && target != null && source.currency != target.currency
    val valid = value.title.isNotBlank() && value.amount.isNotBlank() && value.accountId.isNotBlank() && (!transfer || value.targetAccountId != null && value.targetAmount?.isNotBlank() == true)

    Column(Modifier.fillMaxSize()) {
        EditorHeader(if (value.id == null) "Nuova ricorrenza" else "Modifica ricorrenza", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("EXPENSE" to "Spesa", "INCOME" to "Entrata", "TRANSFER" to "Trasferimento").forEach { (kind, label) ->
                FilterChip(
                    selected = value.kind == kind,
                    onClick = {
                        val targetCandidate = if (kind == "TRANSFER") value.targetAccountId ?: accounts.firstOrNull { it.id != value.accountId }?.id else null
                        onChange(value.copy(kind = kind, targetAccountId = targetCandidate, targetAmount = if (kind == "TRANSFER") value.targetAmount ?: value.amount.removePrefix("-") else null))
                    },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(value.title, { onChange(value.copy(title = it)) }, label = { Text("Titolo") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value.amount.removePrefix("-"), { onChange(value.copy(amount = it)) }, label = { Text(if (transfer) "Importo da prelevare" else "Importo") }, modifier = Modifier.weight(1f), singleLine = true)
                    CurrencyField(value.currency, accounts.map { it.currency }.distinct()) { onChange(value.copy(currency = it)) }
                }
            }
            item { AccountField(accounts, value.accountId, if (transfer) "Da conto" else "Conto") { onChange(value.copy(accountId = it.id, currency = it.currency)) } }
            if (transfer) {
                item { AccountField(accounts.filter { it.id != value.accountId }, value.targetAccountId, "A conto") { onChange(value.copy(targetAccountId = it.id, targetAmount = value.targetAmount ?: value.amount.removePrefix("-"))) } }
                item { OutlinedTextField(value.targetAmount.orEmpty(), { onChange(value.copy(targetAmount = it)) }, label = { Text("Importo da ricevere ${target?.currency.orEmpty()}") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (crossCurrency) {
                    item { OutlinedTextField(value.quotedRate.orEmpty(), { text -> onChange(value.copy(quotedRate = text.trim().takeIf { it.isNotEmpty() })) }, label = { Text("Tasso di cambio quotato (opzionale)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value.feeAmount.orEmpty(), { text -> onChange(value.copy(feeAmount = text.trim().takeIf { it.isNotEmpty() })) }, label = { Text("Commissione") }, modifier = Modifier.weight(1f), singleLine = true)
                            CurrencyField(value.feeCurrency ?: target?.currency ?: source?.currency ?: "DKK", accounts.map { it.currency }.distinct()) { onChange(value.copy(feeCurrency = it)) }
                        }
                    }
                }
            }
            item { SuggestionField("Categoria", value.category, categories) { onChange(value.copy(category = it)) } }
            item { PersonAutocomplete(people, value.personId) { onChange(value.copy(personId = it)) } }
            item { SuggestionField("Tag (multipli)", value.tags, tags, multiple = true) { onChange(value.copy(tags = it)) } }
            item { OutlinedTextField(value.chain, { onChange(value.copy(chain = it)) }, label = { Text("Esercente / catena") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!value.lastBusinessDay, { onChange(value.copy(lastBusinessDay = false, dayOfMonth = value.dayOfMonth ?: LocalDate.now().dayOfMonth)) }); Text("Giorno del mese"); Spacer(Modifier.width(8.dp)); OutlinedTextField(value.dayOfMonth?.toString().orEmpty(), { onChange(value.copy(dayOfMonth = it.toIntOrNull()?.coerceIn(1, 31), lastBusinessDay = false)) }, modifier = Modifier.width(76.dp), singleLine = true) } }
            item { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(value.lastBusinessDay, { onChange(value.copy(lastBusinessDay = true, dayOfMonth = null)) }); Text("Ultimo giorno lavorativo del mese") } }
            item { LocalDateButton("Inizio", value.startDate) { date -> if (date != null) onChange(value.copy(startDate = date)) } }
            item { LocalDateButton("Fine (opzionale)", value.endDate, allowClear = true) { onChange(value.copy(endDate = it)) } }
            item {
                OutlinedTextField(value.reminderDaysBefore?.toString().orEmpty(), { onChange(value.copy(reminderDaysBefore = it.toIntOrNull()?.coerceAtLeast(0))) }, label = { Text("Promemoria: giorni prima") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(onClick = onRequestNotifications) { Text("Abilita notifiche") }
            }
            item { OutlinedTextField(value.notes, { onChange(value.copy(notes = it)) }, label = { Text("Nota") }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(value.enabled, { onChange(value.copy(enabled = it)) }); Spacer(Modifier.width(8.dp)); Text("Attiva") } }
        }
        Button(onClick = onSave, enabled = valid, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Salva ricorrenza") }
    }
}

@Composable
internal fun AccountEditorV2(value: FinanceAccount, onChange: (FinanceAccount) -> Unit, onBack: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        EditorHeader("Conto", onBack)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value.name, { onChange(value.copy(name = it)) }, label = { Text("Nome conto") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value.currency, { onChange(value.copy(currency = it.uppercase())) }, label = { Text("Valuta") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value.openingBalance, { onChange(value.copy(openingBalance = it)) }, label = { Text("Saldo iniziale") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            DateTimeButton("Aperto il", Instant.ofEpochMilli(value.openedAt).toString()) { onChange(value.copy(openedAt = Instant.parse(it).toEpochMilli())) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value.included, { onChange(value.copy(included = it)) }); Text("Includi nel patrimonio") }
            Button(onClick = onSave, enabled = value.name.isNotBlank() && value.currency.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Salva") }
        }
    }
}

@Composable
private fun AttachmentEditor(
    existing: List<FinanceAttachment>,
    pending: List<AttachmentDraft>,
    linkText: String,
    onLinkText: (String) -> Unit,
    onPickFile: () -> Unit,
    onAddLink: () -> Unit,
    onDeleteExisting: (FinanceAttachment) -> Unit,
    onDeletePending: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Allegati", fontWeight = FontWeight.SemiBold)
            Text("SQLite conserva solo URI/link e metadati, non i file binari. Così database e autoexport restano compatti; lo scontrino OCR continua a essere un flusso separato.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onPickFile) { Text("Aggiungi foto o PDF") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(linkText, onLinkText, label = { Text("Link Imgur / Telegram / web") }, modifier = Modifier.weight(1f), singleLine = true)
                TextButton(onClick = onAddLink) { Text("+") }
            }
            existing.forEach { attachment -> AttachmentRow(attachment.title.ifBlank { attachment.uri }, attachment.uri) { onDeleteExisting(attachment) } }
            pending.forEachIndexed { index, attachment -> AttachmentRow(attachment.title.ifBlank { attachment.uri }, attachment.uri) { onDeletePending(index) } }
        }
    }
}

@Composable
private fun AttachmentRow(title: String, uri: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(uri, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onDelete) { Text("×") }
    }
}
