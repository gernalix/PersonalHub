package com.gernalix.personalhub.soldi

import android.Manifest
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
import com.gernalix.personalhub.core.database.capsules.soldi.*
import com.gernalix.personalhub.soldi.receipt.*
import kotlinx.coroutines.CancellationException
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
    val transfers by capsule.transfers.collectAsState(emptyList())
    val macros by capsule.macros.collectAsState(emptyList())
    val recurrences by capsule.recurrences.collectAsState(emptyList())
    val people by capsule.people.collectAsState(emptyList())
    val categories by capsule.categories.collectAsState(emptyList())
    val tags by capsule.tagNames.collectAsState(emptyList())
    val products by capsule.products.collectAsState(emptyList())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { SoldiViewPrefs(context.applicationContext) }

    var tab by rememberSaveable { mutableStateOf(SoldiTab.TRANSACTIONS) }
    var bottom by rememberSaveable { mutableStateOf(BottomDestination.HOME) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var search by rememberSaveable { mutableStateOf("") }
    var viewOptions by remember { mutableStateOf(prefs.load()) }
    var showViewOptions by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<SoldiEditor?>(null) }
    var recurrenceEditor by remember { mutableStateOf<RecurrenceDraft?>(null) }
    var accountEditor by remember { mutableStateOf<FinanceAccount?>(null) }
    var receiptDraft by remember { mutableStateOf<ReceiptImportDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tagsByTransaction by remember { mutableStateOf<Map<Long, List<String>>>(emptyMap()) }
    var projected by remember { mutableStateOf<List<ProjectedOccurrence>>(emptyList()) }
    var handledUuid by rememberSaveable { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
    LaunchedEffect(hubTransactionUuid, transactions, transfers, tagsByTransaction) {
        val uuid = hubTransactionUuid ?: return@LaunchedEffect
        if (handledUuid == uuid) return@LaunchedEffect
        val row = transactions.firstOrNull { it.value.uuid == uuid } ?: return@LaunchedEffect
        val transfer = transfers.firstOrNull { it.sourceTransactionId == row.value.id || it.targetTransactionId == row.value.id }
        if (transfer != null) {
            val source = rowMap[transfer.sourceTransactionId]
            val target = rowMap[transfer.targetTransactionId]
            if (source != null && target != null) {
                editor = SoldiEditor.Transfer(transferState(transfer, source, target, tagsByTransaction[source.value.id].orEmpty()))
            }
        } else {
            editor = SoldiEditor.Transaction(
                row.toDraft(tagsByTransaction[row.value.id].orEmpty()),
                if (BigDecimal(row.value.amount).signum() < 0) EntryKind.EXPENSE else EntryKind.INCOME,
            )
        }
        handledUuid = uuid
    }

    val attachmentOwnerId = when (val current = editor) {
        is SoldiEditor.Transaction -> current.draft.id
        is SoldiEditor.Transfer -> current.state.sourceTransactionId
        null -> null
    }
    val existingAttachments = rememberFinanceAttachments(capsule, attachmentOwnerId)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (editor == null && recurrenceEditor == null && accountEditor == null && receiptDraft == null) {
                if (bottom == BottomDestination.HOME) {
                    SoldiTopBar(
                        tab = tab,
                        onTab = { tab = it },
                        onClose = finish,
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
            if (editor == null && recurrenceEditor == null && accountEditor == null && receiptDraft == null) {
                SoldiBottomBar(bottom) { destination ->
                    if (destination == BottomDestination.MORE) showMore = true else bottom = destination
                }
            }
        },
        floatingActionButton = {
            if (!busy && editor == null && recurrenceEditor == null && accountEditor == null && receiptDraft == null) {
                when (bottom) {
                    BottomDestination.HOME -> if (tab == SoldiTab.TRANSACTIONS) FloatingActionButton(onClick = { editor = SoldiEditor.Transaction(defaultTransaction(accounts), EntryKind.EXPENSE) }) { Text("+") }
                    BottomDestination.ACCOUNTS -> FloatingActionButton(onClick = { accountEditor = FinanceAccount(name = "", currency = accounts.firstOrNull()?.currency ?: "DKK", openedAt = Instant.now().toString()) }) { Text("+") }
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
                        categories = categories,
                        tags = tags,
                        existingAttachments = existingAttachments,
                        onBack = { if (!busy) editor = null },
                        onChange = { if (!busy) editor = it },
                        onSwitchToTransfer = { if (!busy) editor = SoldiEditor.Transfer(defaultTransfer(accounts)) },
                        onRequestNotifications = ::requestNotifications,
                        onDeleteAttachment = { attachment -> runAction { capsule.deleteAttachment(attachment.id) } },
                        onSave = { draft, recurrence, pendingAttachments, saveAndNew ->
                            runAction {
                                val signed = draft.copy(amount = signedAmount(draft.amount, current.kind))
                                val recurrenceId = recurrence?.let { capsule.saveRecurrence(it) }
                                val finalDraft = if (recurrenceId == null) signed else signed.copy(recurrenceId = recurrenceId, occurrenceKey = localDate(signed.occurredAt).toString())
                                val transactionId = capsule.saveTransaction(finalDraft)
                                pendingAttachments.forEach { capsule.addAttachment(transactionId, it) }
                                FinanceReminderScheduler.reschedule(context.applicationContext)
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
                        categories = categories,
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
                                    ),
                                )
                                pendingAttachments.forEach { capsule.addAttachment(transfer.sourceTransactionId, it) }
                                FinanceReminderScheduler.reschedule(context.applicationContext)
                                editor = null
                            }
                        },
                    )
                }

                recurrenceEditor != null -> {
                    RecurrenceEditorV2(
                        value = recurrenceEditor!!,
                        accounts = accounts,
                        people = people,
                        categories = categories,
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
                    onEdit = { rule -> runAction { recurrenceEditor = rule.toDraft(capsule.recurrenceTags(rule.id)) } },
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
                        search,
                        { search = it },
                        accounts,
                        transactions,
                        transfers,
                        macros,
                        tagsByTransaction,
                        viewOptions,
                        { row -> editor = SoldiEditor.Transaction(row.toDraft(tagsByTransaction[row.value.id].orEmpty()), if (BigDecimal(row.value.amount).signum() < 0) EntryKind.EXPENSE else EntryKind.INCOME) },
                        { transfer, source, target -> editor = SoldiEditor.Transfer(transferState(transfer, source, target, tagsByTransaction[source.value.id].orEmpty())) },
                        { editor = SoldiEditor.Transaction(defaultTransaction(accounts), EntryKind.EXPENSE) },
                        { editor = SoldiEditor.Transaction(defaultTransaction(accounts), EntryKind.INCOME) },
                        { editor = SoldiEditor.Transfer(defaultTransfer(accounts)) },
                    )
                    SoldiTab.OVERVIEW -> OverviewScreenV2(month, accounts, transactions.map { it.value }, projected, transfers, viewOptions.ignoreTransfers)
                    SoldiTab.STATISTICS -> StatisticsScreenV2(month, transactions, transfers)
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
