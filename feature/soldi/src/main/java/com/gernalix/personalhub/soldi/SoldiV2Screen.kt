package com.gernalix.personalhub.soldi

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.DataExplorerContract
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.core.database.capsules.soldi.*
import com.gernalix.personalhub.soldi.receipt.*
import com.gernalix.personalhub.soldi.hub.SoldiTransactionHubAdapter
import com.gernalix.personalhub.core.ui.launchSinceWhenCreate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun SoldiV2Screen(
    capsule: FinanceCapsule,
    finish: () -> Unit,
    hubTransactionUuid: String? = null,
) {
    val accounts by capsule.accounts.collectAsState(emptyList())
    val transactions by capsule.transactions.collectAsState(emptyList())
    val allAttachments by capsule.allAttachments.collectAsState(emptyList())
    val photoIndexes by capsule.photoIndexes.collectAsState(emptyList())
    val ownedItems by capsule.ownedItems.collectAsState(emptyList())
    val transfers by capsule.transfers.collectAsState(emptyList())
    val macros by capsule.macros.collectAsState(emptyList())
    val recurrences by capsule.recurrences.collectAsState(emptyList())
    val people by capsule.people.collectAsState(emptyList())
    val places by capsule.places.collectAsState(emptyList())
    val tags by capsule.tagNames.collectAsState(emptyList())
    val products by capsule.products.collectAsState(emptyList())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { SoldiViewPrefs(context.applicationContext) }
    val semanticEngine = remember(capsule) { FinancePhotoSemanticEngine(context.applicationContext, capsule) }
    DisposableEffect(semanticEngine) { onDispose { semanticEngine.close() } }

    var tab by rememberSaveable { mutableStateOf(SoldiTab.TRANSACTIONS) }
    var bottom by rememberSaveable { mutableStateOf(BottomDestination.HOME) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var viewOptions by remember { mutableStateOf(prefs.load()) }
    var showViewOptions by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var globalSearchQuery by rememberSaveable { mutableStateOf("") }
    var globalSearchMode by rememberSaveable { mutableStateOf(SoldiSearchMode.SEARCH) }
    var editor by remember { mutableStateOf<SoldiEditor?>(null) }
    var recurrenceEditor by remember { mutableStateOf<RecurrenceDraft?>(null) }
    var accountEditor by remember { mutableStateOf<FinanceAccount?>(null) }
    var receiptDraft by remember { mutableStateOf<ReceiptImportDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tagsByTransaction by remember { mutableStateOf<Map<Long, List<String>>>(emptyMap()) }
    var projected by remember { mutableStateOf<List<ProjectedOccurrence>>(emptyList()) }
    var handledUuid by rememberSaveable { mutableStateOf<String?>(null) }
    var semanticMatches by remember { mutableStateOf<List<FinanceSemanticMatch>>(emptyList()) }
    var objectMatches by remember { mutableStateOf<List<FinanceSemanticMatch>>(emptyList()) }
    var findObjectDialog by remember { mutableStateOf(false) }
    var semanticBusy by remember { mutableStateOf(false) }

    val photoByTransaction = remember(allAttachments) {
        allAttachments
            .groupBy { it.transactionId }
            .mapNotNull { (transactionId, attachments) ->
                preferredTransactionPhoto(attachments)?.let { transactionId to it }
            }
            .toMap()
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val findObjectGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                semanticBusy = true
                try {
                    objectMatches = semanticEngine.findSimilar(uri, photoIndexes)
                    globalSearchMode = SoldiSearchMode.OBJECT
                    searchOpen = true
                } catch (error: Exception) {
                    snackbar.showSnackbar(error.message ?: "Ricerca foto non riuscita")
                } finally {
                    semanticBusy = false
                }
            }
        }
    }
    val findObjectCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            scope.launch {
                semanticBusy = true
                try {
                    objectMatches = semanticEngine.findSimilar(bitmap, photoIndexes)
                    globalSearchMode = SoldiSearchMode.OBJECT
                    searchOpen = true
                } catch (error: Exception) {
                    snackbar.showSnackbar(error.message ?: "Ricerca foto non riuscita")
                } finally {
                    semanticBusy = false
                }
            }
        }
    }

    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Operazione non riuscita")
            } finally {
                busy = false
            }
        }
    }

    val receiptOcr = remember { ReceiptOcrProcessor() }
    val receiptParser = remember { ReceiptParser() }
    val aliases = remember { ReceiptProductAliasStore(context.applicationContext) }
    val receiptPreferences = remember { ReceiptEnrichmentPreferences(context.applicationContext) }
    val receiptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runAction {
            val raw = receiptOcr.recognize(context, uri)
            receiptDraft = receiptParser.parse(raw).toImportDraft(products, aliases)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { capsule.materializeDueRecurrences() }
        runCatching { FinanceReminderScheduler.reschedule(context.applicationContext) }
    }

    LaunchedEffect(allAttachments.map { Triple(it.id, it.uri, it.createdAt) }) {
        runCatching { semanticEngine.backfill(allAttachments) }
    }

    LaunchedEffect(globalSearchQuery, photoIndexes, searchOpen, globalSearchMode) {
        if (!searchOpen || globalSearchMode != SoldiSearchMode.SEARCH || globalSearchQuery.isBlank()) {
            semanticMatches = emptyList()
        } else {
            delay(180)
            semanticMatches = runCatching {
                semanticEngine.searchText(globalSearchQuery, photoIndexes)
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(transactions.map { it.value.id }) {
        tagsByTransaction = transactions.associate { row ->
            row.value.id to runCatching { capsule.tags(row.value.id) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(month, recurrences.map { it.id to it.updatedAt }) {
        val from = minOf(LocalDate.now(), month.atDay(1))
        projected = runCatching { capsule.projectedOccurrences(from, month.atEndOfMonth()) }.getOrDefault(emptyList())
    }

    val rowMap = remember(transactions) { transactions.associateBy { it.value.id } }

    fun openTransaction(row: TransactionView) {
        val transfer = transfers.firstOrNull {
            it.sourceTransactionId == row.value.id || it.targetTransactionId == row.value.id
        }
        if (transfer != null) {
            val source = rowMap[transfer.sourceTransactionId]
            val target = rowMap[transfer.targetTransactionId]
            if (source != null && target != null) {
                runAction {
                    editor = SoldiEditor.Transfer(transferState(
                        transfer, source, target, tagsByTransaction[source.value.id].orEmpty(),
                        capsule.contextRefs("transaction", source.value.uuid),
                    ))
                }
            }
        } else {
            editor = SoldiEditor.Transaction(
                row.toDraft(tagsByTransaction[row.value.id].orEmpty()),
                if (BigDecimal(row.value.amount).signum() < 0) EntryKind.EXPENSE else EntryKind.INCOME,
            )
        }
    }

    LaunchedEffect(hubTransactionUuid, transactions, transfers, tagsByTransaction) {
        val uuid = hubTransactionUuid ?: return@LaunchedEffect
        if (handledUuid == uuid) return@LaunchedEffect
        val row = transactions.firstOrNull { it.value.uuid == uuid } ?: return@LaunchedEffect
        openTransaction(row)
        handledUuid = uuid
    }

    val attachmentOwnerId = when (val current = editor) {
        is SoldiEditor.Transaction -> current.draft.id
        is SoldiEditor.Transfer -> current.state.sourceTransactionId
        null -> null
    }
    val existingAttachments = rememberFinanceAttachments(capsule, attachmentOwnerId)
    val currentOwnedItems = remember(ownedItems, attachmentOwnerId) {
        if (attachmentOwnerId == null) emptyList() else ownedItems.filter { it.sourceTransactionId == attachmentOwnerId }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (editor == null && recurrenceEditor == null && accountEditor == null && receiptDraft == null && !searchOpen) {
                if (bottom == BottomDestination.HOME) {
                    SoldiTopBar(
                        tab = tab,
                        onTab = { tab = it },
                        onClose = finish,
                        onSearch = {
                            globalSearchMode = SoldiSearchMode.SEARCH
                            searchOpen = true
                        },
                        onViewOptions = { showViewOptions = true },
                        onMore = { showMore = true },
                    )
                } else {
                    ManagementTopBar(
                        title = when (bottom) {
                            BottomDestination.ACCOUNTS -> "Conti"
                            BottomDestination.CATEGORIES -> "Categorie"
                            BottomDestination.RECURRENCES -> "Ricorrenze"
                            else -> "Soldi"
                        },
                        onHome = { bottom = BottomDestination.HOME },
                        onMore = { showMore = true },
                    )
                }
            }
        },
        bottomBar = {
            if (editor == null && recurrenceEditor == null && accountEditor == null && receiptDraft == null && !searchOpen) {
                SoldiBottomBar(bottom) { destination ->
                    if (destination == BottomDestination.MORE) showMore = true else bottom = destination
                }
            }
        },
        floatingActionButton = {
            if (!busy && editor == null && recurrenceEditor == null && accountEditor == null && receiptDraft == null && !searchOpen) {
                when (bottom) {
                    BottomDestination.HOME -> if (tab == SoldiTab.TRANSACTIONS) FloatingActionButton(onClick = { editor = SoldiEditor.Transaction(defaultTransaction(accounts), EntryKind.EXPENSE) }) { Text("+") }
                    BottomDestination.ACCOUNTS -> FloatingActionButton(onClick = { accountEditor = FinanceAccount(name = "", currency = accounts.firstOrNull()?.currency ?: "DKK", openedAt = System.currentTimeMillis()) }) { Text("+") }
                    BottomDestination.RECURRENCES -> FloatingActionButton(onClick = {
                        val draft = defaultRecurrence(accounts)
                        if (draft == null) scope.launch { snackbar.showSnackbar("Crea prima almeno un conto") } else recurrenceEditor = draft
                    }) { Text("+") }
                    else -> Unit
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                editor is SoldiEditor.Transaction -> {
                    val current = editor as SoldiEditor.Transaction
                    TransactionEditorV2(
                        state = current,
                        accounts = accounts,
                        people = people,
                        places = places,
                        tags = tags,
                        existingAttachments = existingAttachments,
                        ownedItems = currentOwnedItems,
                        onBack = { if (!busy) editor = null },
                        onChange = { if (!busy) editor = it },
                        onSwitchToTransfer = { if (!busy) editor = SoldiEditor.Transfer(defaultTransfer(accounts)) },
                        onRequestNotifications = ::requestNotifications,
                        onDeleteAttachment = { attachment -> runAction { capsule.deleteAttachment(attachment.id) } },
                        onTrackOwnedItem = { name ->
                            val transactionId = current.draft.id
                            if (transactionId != null) runAction {
                                capsule.trackOwnedItem(
                                    transactionId,
                                    name,
                                    preferredTransactionPhoto(existingAttachments)?.id,
                                )
                            }
                        },
                        onRemoveOwnedItem = { item -> runAction { capsule.removeOwnedItem(item.uuid) } },
                        saving = busy,
                        onSave = { draft, recurrence, pendingAttachments, saveAndNew, createSinceWhen, selectedSourceId ->
                            runAction {
                                val signed = draft.copy(amount = signedAmount(draft.amount, current.kind))
                                val recurrenceId = recurrence?.let { capsule.saveRecurrence(it) }
                                val identified = signed.copy(transactionUuid = signed.transactionUuid ?: java.util.UUID.randomUUID().toString())
                                val finalDraft = if (recurrenceId == null) identified else identified.copy(recurrenceId = recurrenceId, occurrenceKey = localDate(identified.occurredAt).toString())
                                val transactionId = capsule.saveTransaction(finalDraft)
                                pendingAttachments.forEach { capsule.addAttachment(transactionId, it) }
                                FinanceReminderScheduler.reschedule(context.applicationContext)
                                if (createSinceWhen) {
                                    SoldiTransactionHubAdapter(context).sinceWhenSource(requireNotNull(finalDraft.transactionUuid))
                                        ?.let { context.launchSinceWhenCreate(it, selectedSourceId) }
                                }
                                editor = if (saveAndNew) SoldiEditor.Transaction(defaultTransaction(accounts), current.kind) else null
                            }
                        },
                    )
                }

                editor is SoldiEditor.Transfer -> {
                    val current = (editor as SoldiEditor.Transfer).state
                    TransferEditorV2(
                        state = current,
                        accounts = accounts,
                        people = people,
                        tags = tags,
                        existingAttachments = existingAttachments,
                        onBack = { if (!busy) editor = null },
                        onChange = { if (!busy) editor = SoldiEditor.Transfer(it) },
                        onRequestNotifications = ::requestNotifications,
                        onDeleteAttachment = { attachment -> runAction { capsule.deleteAttachment(attachment.id) } },
                        onSave = { state, recurrence, pendingAttachments ->
                            runAction {
                                val recurrenceId = recurrence?.let { capsule.saveRecurrence(it) }
                                val transfer = capsule.saveTransfer(
                                    TransferDraft(
                                        transferId = state.transferId,
                                        title = state.title,
                                        sourceAccountId = requireNotNull(state.sourceAccountId),
                                        targetAccountId = requireNotNull(state.targetAccountId),
                                        sourceAmount = state.sourceAmount,
                                        targetAmount = state.targetAmount,
                                        quotedRate = state.quotedRate.takeIf(String::isNotBlank),
                                        feeAmount = state.feeAmount.takeIf(String::isNotBlank),
                                        feeCurrency = state.feeCurrency.takeIf(String::isNotBlank),
                                        notes = state.notes,
                                        tags = state.tags,
                                        personId = state.personId,
                                        category = state.category,
                                        occurredAt = state.occurredAt,
                                        reminderAt = state.reminderAt,
                                        recurrenceId = recurrenceId,
                                        occurrenceKey = recurrenceId?.let { localDate(state.occurredAt).toString() },
                                        contextRefs = state.contextRefs,
                                    ),
                                )
                                pendingAttachments.forEach { capsule.addAttachment(transfer.sourceTransactionId, it) }
                                FinanceReminderScheduler.reschedule(context.applicationContext)
                                editor = null
                            }
                        },
                    )
                }

                searchOpen -> {
                    SoldiSearchScreen(
                        mode = globalSearchMode,
                        query = globalSearchQuery,
                        onQueryChange = { globalSearchQuery = it },
                        rows = transactions,
                        accounts = accounts,
                        tagsByTransaction = tagsByTransaction,
                        attachments = allAttachments,
                        photoIndexes = photoIndexes,
                        ownedItems = ownedItems,
                        semanticMatches = semanticMatches,
                        objectMatches = objectMatches,
                        onBack = {
                            if (globalSearchMode == SoldiSearchMode.PHOTOS || globalSearchMode == SoldiSearchMode.OBJECT) {
                                globalSearchMode = SoldiSearchMode.SEARCH
                            } else {
                                searchOpen = false
                            }
                        },
                        onPhotos = { globalSearchMode = SoldiSearchMode.PHOTOS },
                        onFindObject = { if (!semanticBusy) findObjectDialog = true },
                        onSearchMode = { globalSearchMode = SoldiSearchMode.SEARCH },
                        onOpenTransaction = ::openTransaction,
                    )
                }

                recurrenceEditor != null -> {
                    RecurrenceEditorV2(
                        value = recurrenceEditor!!,
                        accounts = accounts,
                        people = people,
                        tags = tags,
                        onChange = { if (!busy) recurrenceEditor = it },
                        onBack = { if (!busy) recurrenceEditor = null },
                        onRequestNotifications = ::requestNotifications,
                        onSave = {
                            runAction {
                                capsule.saveRecurrence(recurrenceEditor!!)
                                capsule.materializeDueRecurrences()
                                FinanceReminderScheduler.reschedule(context.applicationContext)
                                recurrenceEditor = null
                            }
                        },
                    )
                }

                accountEditor != null -> {
                    AccountEditorV2(
                        value = accountEditor!!,
                        onChange = { if (!busy) accountEditor = it },
                        onBack = { if (!busy) accountEditor = null },
                        onSave = { runAction { capsule.saveAccount(accountEditor!!); accountEditor = null } },
                    )
                }

                receiptDraft != null -> {
                    ReceiptImportScreen(
                        draft = receiptDraft!!,
                        products = products,
                        accounts = accounts,
                        chatGptEnabled = receiptPreferences.chatGptEnabled(),
                        busy = busy,
                        onChange = { if (!busy) receiptDraft = it },
                        onSave = {
                            val draft = receiptDraft!!
                            runAction {
                                val imported = capsule.importReceipt(
                                    FinanceReceiptImport(
                                        merchant = draft.merchant,
                                        occurredAt = receiptOccurredAt(draft.dateTime),
                                        currency = draft.currency,
                                        accountId = draft.accountId,
                                        items = draft.lines.filter { it.include }.map { line ->
                                            FinanceReceiptImportItem(line.rawDescription, line.description, line.productId, line.totalPrice)
                                        },
                                    ),
                                )
                                imported.forEach { aliases.remember(it.rawDescription, it.product) }
                                receiptDraft = null
                            }
                        },
                    )
                }

                bottom == BottomDestination.ACCOUNTS -> AccountsScreenV2(
                    accounts,
                    transactions.map { it.value },
                    { accountEditor = it },
                    { account, included -> runAction { capsule.setIncluded(account.id, included) } },
                )

                bottom == BottomDestination.CATEGORIES -> CategoriesScreenV2(transactions)

                bottom == BottomDestination.RECURRENCES -> RecurrencesScreenV2(
                    recurrences = recurrences,
                    nextOccurrence = { capsule.nextOccurrence(it) },
                    onEdit = { rule -> runAction {
                        recurrenceEditor = rule.toDraft(capsule.recurrenceTags(rule.id), capsule.contextRefs("recurrence", rule.id))
                    } },
                    onToggle = { rule, enabled -> runAction {
                        capsule.setRecurrenceEnabled(rule.id, enabled)
                        FinanceReminderScheduler.reschedule(context.applicationContext)
                    } },
                    onChangeNextAmount = { rule, date, amount, targetAmount, editScope -> runAction {
                        capsule.editRecurrenceAmount(rule.id, date, amount, targetAmount, editScope)
                    } },
                )

                else -> when (tab) {
                    SoldiTab.TRANSACTIONS -> TransactionsScreenV2(
                        month,
                        { month = it },
                        accounts,
                        transactions,
                        transfers,
                        macros,
                        tagsByTransaction,
                        photoByTransaction,
                        viewOptions,
                        ::openTransaction,
                        { transfer, source, target -> runAction {
                            editor = SoldiEditor.Transfer(transferState(
                                transfer, source, target, tagsByTransaction[source.value.id].orEmpty(),
                                capsule.contextRefs("transaction", source.value.uuid),
                            ))
                        } },
                        { editor = SoldiEditor.Transaction(defaultTransaction(accounts), EntryKind.EXPENSE) },
                        { editor = SoldiEditor.Transaction(defaultTransaction(accounts), EntryKind.INCOME) },
                        { editor = SoldiEditor.Transfer(defaultTransfer(accounts)) },
                        onCreateSinceWhen = { row -> runAction {
                            val source = SoldiTransactionHubAdapter(context.applicationContext)
                                .sinceWhenSource(row.value.uuid)
                            if (source != null && source.timestampSources.isNotEmpty()) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, HubDeepLinkContract.sinceWhenCreateUri(source)))
                            }
                        } },
                    )
                    SoldiTab.OVERVIEW -> OverviewScreenV2(month, accounts, transactions.map { it.value }, projected, transfers, viewOptions.ignoreTransfers)
                    SoldiTab.STATISTICS -> StatisticsScreenV2(month, transactions, transfers, tagsByTransaction)
                    SoldiTab.CHARTS -> ChartsScreenV2(month, transactions, transfers)
                    SoldiTab.CALENDAR -> CalendarScreenV2(month, { month = it }, accounts, transactions, projected, transfers)
                }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    if (showViewOptions) {
        ViewOptionsDialog(viewOptions, { viewOptions = it; prefs.save(it) }, { showViewOptions = false })
    }

    if (findObjectDialog) {
        AlertDialog(
            onDismissRequest = { findObjectDialog = false },
            title = { Text("Trova questo oggetto") },
            text = { Text("Scatta una foto oppure scegli un'immagine. Vedrai fino a 10 possibili corrispondenze; il risultato non viene trattato come un'identificazione certa.") },
            confirmButton = {
                TextButton(onClick = {
                    findObjectDialog = false
                    findObjectCamera.launch(null)
                }) { Text("Fotocamera") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { findObjectDialog = false }) { Text("Annulla") }
                    TextButton(onClick = {
                        findObjectDialog = false
                        findObjectGallery.launch("image/*")
                    }) { Text("Galleria") }
                }
            },
        )
    }

    if (showMore) {
        AlertDialog(
            onDismissRequest = { showMore = false },
            title = { Text("Altro") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("Importa scontrino") },
                        supportingContent = { Text("OCR separato dalla creazione manuale della transazione") },
                        modifier = Modifier.clickable { showMore = false; receiptLauncher.launch("image/*") },
                    )
                    ListItem(headlineContent = { Text("Opzioni di visualizzazione") }, modifier = Modifier.clickable { showMore = false; showViewOptions = true })
                    ListItem(
                        headlineContent = { Text("Esplora dati") },
                        supportingContent = { Text("Datasette · transazioni") },
                        modifier = Modifier.clickable {
                            showMore = false
                            context.startActivity(
                                DataExplorerContract.intent(context.packageName, "finance_transactions"),
                            )
                        },
                    )
                    ListItem(headlineContent = { Text("Nuovo trasferimento") }, modifier = Modifier.clickable { showMore = false; editor = SoldiEditor.Transfer(defaultTransfer(accounts)) })
                }
            },
            confirmButton = { TextButton(onClick = { showMore = false }) { Text("Chiudi") } },
        )
    }
}

@Composable
private fun rememberFinanceAttachments(capsule: FinanceCapsule, transactionId: Long?): List<FinanceAttachment> {
    if (transactionId == null) return emptyList()
    val flow = remember(transactionId) { capsule.attachments(transactionId) }
    val rows by flow.collectAsState(emptyList())
    return rows
}

@Composable
private fun ManagementTopBar(title: String, onHome: () -> Unit, onMore: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onHome) { Text("‹") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onMore) { Text("⋮") }
        }
        HorizontalDivider()
    }
}
