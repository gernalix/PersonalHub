package com.gernalix.personalhub.soldi

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun BankingScreen(
    repository: BankingRepository = BankingRepository(LocalContext.current),
    localAccounts: List<FinanceAccount> = emptyList(),
    localRows: List<TransactionView> = emptyList(),
    onCreateAccount: suspend (FinanceAccount) -> Unit = {},
    onSuggestion: (BankingSuggestion) -> Unit = {},
    onRequestNotifications: () -> Unit = {},
) {
    val context = LocalContext.current
    val mappingErrorMessage = stringResource(R.string.banking_mapping_error)
    val invalidAlertThresholdMessage = stringResource(R.string.banking_alert_invalid_threshold)
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(repository.cached()) }
    var configuration by remember { mutableStateOf(repository.configuration()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(!configuration.configured) }
    var filters by remember { mutableStateOf(BankingFilters()) }
    var reconciliationState by remember { mutableStateOf(repository.reconciliationState()) }
    var mappingAccount by remember { mutableStateOf<BankingAccount?>(null) }
    var alertsOpen by remember { mutableStateOf(false) }
    val reconciliation = remember(snapshot, reconciliationState, localAccounts, localRows) {
        snapshot?.let { reconcileBanking(it, reconciliationState, localAccounts, localRows) }
    }

    fun refresh() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                val refreshed = repository.refresh()
                snapshot = refreshed
                reconciliationState = repository.processAlerts(refreshed, localAccounts, localRows)
                message = if (refreshed.partialAccounts.isNotEmpty()) {
                    null
                } else {
                    repositoryMessage(context, BankingMessage.UPDATED)
                }
            } catch (error: BankingException) {
                message = repositoryMessage(context, error.kind.toMessage())
                snapshot = repository.cached() ?: snapshot
            } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.banking_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(cacheSummary(snapshot?.cache), style = MaterialTheme.typography.bodySmall)
                snapshot?.cache?.nextRefreshEligibleAt?.let {
                    Text(stringResource(R.string.banking_server_refresh_eligible, formatInstant(it)), style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { setup = true }) { Text(stringResource(R.string.banking_settings)) }
            Button(onClick = ::refresh, enabled = configuration.configured && !busy) { Text(stringResource(R.string.banking_refresh)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error) }

        when {
            !configuration.configured -> BankingEmpty(
                title = stringResource(R.string.banking_not_configured),
                body = stringResource(R.string.banking_not_configured_body),
                action = { setup = true },
            )
            snapshot == null -> BankingEmpty(
                title = stringResource(R.string.banking_no_cache),
                body = stringResource(R.string.banking_no_cache_body),
                action = ::refresh,
            )
            snapshot!!.accounts.isEmpty() -> BankingEmpty(
                title = stringResource(R.string.banking_no_accounts),
                body = stringResource(R.string.banking_no_accounts_body),
                action = ::refresh,
            )
            else -> BankingContent(
                snapshot = snapshot!!,
                filters = filters,
                localAccounts = localAccounts,
                reconciliation = requireNotNull(reconciliation),
                state = reconciliationState,
                onFilters = { filters = it },
                onMap = { mappingAccount = it },
                onSuggestion = onSuggestion,
                onAlerts = { alertsOpen = true },
            )
        }
    }

    if (setup) BankingSetupDialog(
        configuration = configuration,
        onDismiss = { setup = false },
        onSave = { url, token ->
            runCatching { repository.configure(url, token) }
                .onSuccess {
                    configuration = repository.configuration(); setup = false; message = null
                }
                .onFailure { message = repositoryMessage(context, BankingMessage.INVALID_CONFIGURATION) }
        },
        onDisconnect = {
            repository.disconnect(); configuration = repository.configuration(); snapshot = null; setup = false
            reconciliationState = BankingReconciliationState()
            message = repositoryMessage(context, BankingMessage.DISCONNECTED)
        },
    )

    mappingAccount?.let { bankAccount ->
        BankingAccountMappingDialog(
            bankAccount = bankAccount,
            localAccounts = localAccounts,
            state = reconciliationState,
            onDismiss = { mappingAccount = null },
            onLink = { localAccount ->
                runCatching { repository.linkAccount(bankAccount, localAccount) }
                    .onSuccess { reconciliationState = it; mappingAccount = null; message = null }
                    .onFailure { message = mappingErrorMessage }
            },
            onCreate = { currency ->
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val created = FinanceAccount(name = bankAccount.bank, currency = currency)
                            onCreateAccount(created)
                            reconciliationState = repository.linkAccount(bankAccount, created)
                            mappingAccount = null
                            message = null
                        } catch (_: Exception) {
                            message = mappingErrorMessage
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            onUnlink = {
                reconciliationState = repository.unlinkAccount(bankAccount.id)
                mappingAccount = null
            },
        )
    }

    if (alertsOpen) BankingAlertSettingsDialog(
        preferences = reconciliationState.alertPreferences,
        onDismiss = { alertsOpen = false },
        onSave = { enabled, threshold ->
            runCatching { repository.saveAlertPreferences(enabled, threshold) }
                .onSuccess {
                    reconciliationState = it
                    alertsOpen = false
                    message = null
                    if (enabled) onRequestNotifications()
                }
                .onFailure { message = invalidAlertThresholdMessage }
        },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BankingContent(
    snapshot: BankingSnapshot,
    filters: BankingFilters,
    localAccounts: List<FinanceAccount>,
    reconciliation: BankingReconciliationResult,
    state: BankingReconciliationState,
    onFilters: (BankingFilters) -> Unit,
    onMap: (BankingAccount) -> Unit,
    onSuggestion: (BankingSuggestion) -> Unit,
    onAlerts: () -> Unit,
) {
    val rows = remember(snapshot.transactions, filters) { filterBankingTransactions(snapshot.transactions, filters) }
    val accountLabels = remember(snapshot.accounts) {
        snapshot.accounts.groupBy(BankingAccount::bank).flatMap { (bank, accounts) ->
            accounts.mapIndexed { index, account -> account.id to "$bank ${index + 1}" }
        }.toMap()
    }
    val banks = snapshot.accounts.map(BankingAccount::bank).distinct().sorted()
    val currencies = snapshot.accounts.flatMap(BankingAccount::currencies).distinct().sorted()
    val localById = localAccounts.associateBy(FinanceAccount::id)
    val comparisons = reconciliation.comparisons.associateBy(BankingComparison::bankAccountId)
    val suggestions = reconciliation.suggestions.groupBy(BankingSuggestion::bankAccountId)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            snapshot.partialAccounts.takeIf(Set<String>::isNotEmpty)?.let {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.banking_partial_error)) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.banking_bank_balances), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onAlerts) { Text(stringResource(R.string.banking_alert_settings)) }
            }
            Text(
                if (state.alertPreferences.enabled) {
                    stringResource(R.string.banking_alert_enabled, formatAmount(state.alertPreferences.threshold))
                } else {
                    stringResource(R.string.banking_alert_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(snapshot.accounts, key = BankingAccount::id) { account ->
            val comparison = requireNotNull(comparisons[account.id])
            val linked = comparison.localAccountId?.let(localById::get)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(accountLabels.getValue(account.id), fontWeight = FontWeight.SemiBold)
                            Text(
                                linked?.let { stringResource(R.string.banking_mapped_to, it.name, it.currency) }
                                    ?: stringResource(R.string.banking_unmapped),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onMap(account) }) { Text(stringResource(R.string.banking_change_mapping)) }
                    }
                    snapshot.balances.filter { it.accountId == account.id }.forEach { balance ->
                        Text("${balanceTypeLabel(balance.type)}  ${formatAmount(balance.amount)} ${balance.currency}")
                    }
                    HorizontalDivider()
                    BankingComparisonBlock(comparison)
                    suggestions[account.id].orEmpty().forEach { suggestion ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(suggestion.description)
                                Text(
                                    "${formatDate(suggestion.date)} · ${formatAmount(suggestion.amount)} ${suggestion.currency}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { onSuggestion(suggestion) }) {
                                Text(stringResource(R.string.banking_review_suggestion))
                            }
                        }
                    }
                }
            }
        }
        item {
            val totals = comparableBankingTotals(snapshot.balances)
            if (totals.isNotEmpty()) {
                Text(stringResource(R.string.banking_comparable_totals), style = MaterialTheme.typography.titleMedium)
                totals.forEach { total -> Text("${balanceTypeLabel(total.type)} · ${formatAmount(total.amount)} ${total.currency}") }
                Text(stringResource(R.string.banking_totals_note), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.banking_transactions), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = filters.query,
                onValueChange = { onFilters(filters.copy(query = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.banking_search)) },
                singleLine = true,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BankingChoice(stringResource(R.string.banking_bank), filters.bank, banks) { onFilters(filters.copy(bank = it)) }
                BankingChoice(stringResource(R.string.banking_account), filters.accountId,
                    snapshot.accounts.map { it.id }, labels = accountLabels) { onFilters(filters.copy(accountId = it)) }
                BankingChoice(stringResource(R.string.banking_currency), filters.currency, currencies) { onFilters(filters.copy(currency = it)) }
                BankingChoice(stringResource(R.string.banking_status), filters.status, listOf("BOOK", "PDNG")) { onFilters(filters.copy(status = it)) }
                BankingChoice(stringResource(R.string.banking_period), filters.days.toString(), listOf("7", "30", "90"), allowAll = false) {
                    onFilters(filters.copy(days = it?.toIntOrNull() ?: 30))
                }
            }
            Text(stringResource(R.string.banking_results, rows.size), style = MaterialTheme.typography.bodySmall)
        }
        items(rows, key = { "${it.accountId}:${it.id}" }) { row ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.description ?: stringResource(R.string.banking_description_missing), Modifier.weight(1f))
                        Text("${formatAmount(row.amount)} ${row.currency}", fontWeight = FontWeight.SemiBold)
                    }
                    Text("${accountLabels[row.accountId] ?: row.bank} · ${row.date?.let(::formatDate) ?: stringResource(R.string.banking_date_missing)}")
                    Text(statusLabel(row.status), style = MaterialTheme.typography.labelMedium,
                        color = if (row.status == "PDNG") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (rows.isEmpty()) item { Text(stringResource(R.string.banking_no_transactions), Modifier.padding(vertical = 24.dp)) }
    }
}

@Composable
private fun BankingComparisonBlock(comparison: BankingComparison) {
    if (comparison.state != BankingComparisonState.COMPARABLE) {
        Text(comparisonStateLabel(comparison.state), color = MaterialTheme.colorScheme.tertiary)
        return
    }
    val currency = requireNotNull(comparison.currency)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(stringResource(R.string.banking_compare_bank), style = MaterialTheme.typography.labelMedium)
            Text("${formatAmount(requireNotNull(comparison.bankBalance))} $currency")
        }
        Column {
            Text(stringResource(R.string.banking_compare_ph), style = MaterialTheme.typography.labelMedium)
            Text("${formatAmount(requireNotNull(comparison.phBalance))} $currency")
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.banking_compare_difference), style = MaterialTheme.typography.labelMedium)
            Text("${formatAmount(requireNotNull(comparison.difference))} $currency", fontWeight = FontWeight.SemiBold)
        }
    }
    comparison.fetchedAt?.let {
        Text(stringResource(R.string.banking_compare_reference, formatInstant(it)), style = MaterialTheme.typography.bodySmall)
    }
    Text(comparisonCauseLabel(requireNotNull(comparison.cause)), style = MaterialTheme.typography.bodySmall)
    if (comparison.pendingCount > 0) {
        Text(stringResource(R.string.banking_pending_separate, comparison.pendingCount), style = MaterialTheme.typography.bodySmall)
    }
    if (comparison.futurePhCount > 0) {
        Text(stringResource(R.string.banking_future_separate, comparison.futurePhCount), style = MaterialTheme.typography.bodySmall)
    }
    if (comparison.ambiguousCount > 0) {
        Text(stringResource(R.string.banking_ambiguous_matches, comparison.ambiguousCount), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun comparisonStateLabel(value: BankingComparisonState): String = stringResource(when (value) {
    BankingComparisonState.COMPARABLE -> R.string.banking_comparable
    BankingComparisonState.UNMAPPED -> R.string.banking_compare_unmapped
    BankingComparisonState.MAPPING_INVALID -> R.string.banking_compare_mapping_invalid
    BankingComparisonState.CURRENCY_MISMATCH -> R.string.banking_compare_currency_mismatch
    BankingComparisonState.STALE -> R.string.banking_compare_stale
    BankingComparisonState.PARTIAL -> R.string.banking_compare_partial
    BankingComparisonState.INCOMPLETE -> R.string.banking_compare_incomplete
    BankingComparisonState.NO_BOOKED_BALANCE -> R.string.banking_compare_no_booked
    BankingComparisonState.REFERENCE_DATE_MISMATCH -> R.string.banking_compare_reference_mismatch
    BankingComparisonState.LOCAL_ACCOUNT_NOT_OPEN -> R.string.banking_compare_local_not_open
})

@Composable
private fun comparisonCauseLabel(value: BankingComparisonCause): String = stringResource(when (value) {
    BankingComparisonCause.BALANCED -> R.string.banking_cause_balanced
    BankingComparisonCause.MISSING_PH_TRANSACTIONS -> R.string.banking_cause_missing
    BankingComparisonCause.PENDING_SEPARATE -> R.string.banking_cause_pending
    BankingComparisonCause.FUTURE_PH_SEPARATE -> R.string.banking_cause_future
    BankingComparisonCause.UNCONFIRMED -> R.string.banking_cause_unconfirmed
})

@Composable
private fun BankingChoice(
    label: String,
    selected: String?,
    values: List<String>,
    labels: Map<String, String> = emptyMap(),
    allowAll: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected != null, onClick = { expanded = true }, label = {
            Text("$label: ${selected?.let { labels[it] ?: it } ?: stringResource(R.string.banking_all)}")
        })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowAll) DropdownMenuItem(text = { Text(stringResource(R.string.banking_all)) }, onClick = { expanded = false; onSelect(null) })
            values.forEach { value -> DropdownMenuItem(text = { Text(labels[value] ?: value) }, onClick = { expanded = false; onSelect(value) }) }
        }
    }
}

@Composable
private fun BankingAccountMappingDialog(
    bankAccount: BankingAccount,
    localAccounts: List<FinanceAccount>,
    state: BankingReconciliationState,
    onDismiss: () -> Unit,
    onLink: (FinanceAccount) -> Unit,
    onCreate: (String) -> Unit,
    onUnlink: () -> Unit,
) {
    val currentLocalId = state.accountLinks[bankAccount.id]
    val usedLocalIds = state.accountLinks.filterKeys { it != bankAccount.id }.values.toSet()
    val eligible = localAccounts.filter { it.currency in bankAccount.currencies && it.id !in usedLocalIds }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.banking_mapping_title, bankAccount.bank)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.banking_mapping_body))
                eligible.forEach { account ->
                    FilledTonalButton(onClick = { onLink(account) }, Modifier.fillMaxWidth()) {
                        Text("${account.name} · ${account.currency}${if (account.id == currentLocalId) " ✓" else ""}")
                    }
                }
                bankAccount.currencies.distinct().sorted().forEach { currency ->
                    OutlinedButton(onClick = { onCreate(currency) }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.banking_create_account, currency))
                    }
                }
                if (currentLocalId != null) {
                    TextButton(onClick = onUnlink) { Text(stringResource(R.string.banking_unlink)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BankingAlertSettingsDialog(
    preferences: BankingAlertPreferences,
    onDismiss: () -> Unit,
    onSave: (Boolean, BigDecimal) -> Unit,
) {
    var enabled by remember(preferences) { mutableStateOf(preferences.enabled) }
    var threshold by remember(preferences) {
        mutableStateOf(preferences.threshold.takeIf { it.signum() > 0 }?.toPlainString() ?: "10")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.banking_alert_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.banking_alert_settings_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text(stringResource(R.string.banking_alert_enable))
                }
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.banking_alert_threshold)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                Text(stringResource(R.string.banking_alert_privacy), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = runCatching { BigDecimal(threshold.trim().replace(',', '.')) }.getOrDefault(BigDecimal.ZERO)
                onSave(enabled, parsed)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BankingSetupDialog(
    configuration: BankingConfiguration,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var url by remember(configuration) { mutableStateOf(configuration.baseUrl) }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.banking_setup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.banking_setup_body))
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.banking_url)) }, singleLine = true)
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.banking_token)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (configuration.configured) TextButton(onClick = onDisconnect) { Text(stringResource(R.string.banking_disconnect)) }
            }
        },
        confirmButton = { Button(onClick = { onSave(url, token) }, enabled = token.isNotBlank()) { Text(stringResource(R.string.banking_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BankingEmpty(title: String, body: String, action: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp)); Text(body); Spacer(Modifier.height(16.dp))
        Button(onClick = action) { Text(stringResource(R.string.banking_continue)) }
    }
}

private enum class BankingMessage { UPDATED, AUTHORIZATION, RATE_LIMIT, OFFLINE, SERVER, INVALID_RESPONSE, INVALID_CONFIGURATION, DISCONNECTED }
private fun BankingFailure.toMessage() = when (this) {
    BankingFailure.AUTHORIZATION -> BankingMessage.AUTHORIZATION
    BankingFailure.RATE_LIMIT -> BankingMessage.RATE_LIMIT
    BankingFailure.OFFLINE -> BankingMessage.OFFLINE
    BankingFailure.SERVER -> BankingMessage.SERVER
    BankingFailure.INVALID_RESPONSE -> BankingMessage.INVALID_RESPONSE
}

private fun repositoryMessage(context: Context, message: BankingMessage): String = context.getString(when (message) {
    BankingMessage.UPDATED -> R.string.banking_updated
    BankingMessage.AUTHORIZATION -> R.string.banking_authorization_error
    BankingMessage.RATE_LIMIT -> R.string.banking_rate_limit
    BankingMessage.OFFLINE -> R.string.banking_offline
    BankingMessage.SERVER -> R.string.banking_server_error
    BankingMessage.INVALID_RESPONSE -> R.string.banking_invalid_response
    BankingMessage.INVALID_CONFIGURATION -> R.string.banking_invalid_configuration
    BankingMessage.DISCONNECTED -> R.string.banking_disconnected
})

@Composable
private fun cacheSummary(cache: BankingCacheInfo?): String = when {
    cache?.fetchedAt == null -> stringResource(R.string.banking_never_updated)
    bankingCacheIsStale(cache) -> stringResource(R.string.banking_last_update_stale, formatInstant(cache.fetchedAt))
    else -> stringResource(R.string.banking_last_update, formatInstant(cache.fetchedAt))
}

internal fun bankingCacheIsStale(cache: BankingCacheInfo, now: Instant = Instant.now()): Boolean =
    cache.stale || cache.fetchedAt == null || now.epochSecond - cache.fetchedAt.epochSecond >= 6 * 60 * 60

@Composable private fun balanceTypeLabel(value: String): String = stringResource(when (value) {
    "ITAV" -> R.string.banking_available
    "ITBD" -> R.string.banking_booked_balance
    else -> R.string.banking_other_balance
})
@Composable private fun statusLabel(value: String): String = stringResource(if (value == "PDNG") R.string.banking_pending else R.string.banking_posted)
private fun formatAmount(value: BigDecimal) = value.stripTrailingZeros().toPlainString()
private fun formatDate(value: java.time.LocalDate) = value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
private fun formatInstant(value: Instant) = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(value)
