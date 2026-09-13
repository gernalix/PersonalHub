package com.gernalix.personalhub.soldi.receipt

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceProduct
import com.gernalix.personalhub.soldi.R

@Composable
fun ReceiptImportScreen(
    draft: ReceiptImportDraft,
    products: List<FinanceProduct>,
    accounts: List<FinanceAccount>,
    chatGptEnabled: Boolean,
    busy: Boolean,
    onChange: (ReceiptImportDraft) -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    var productPickerLine by remember { mutableStateOf<Int?>(null) }
    var accountPicker by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf(false) }

    val included = draft.lines.filter { it.include }
    val canSave = included.isNotEmpty() && included.all {
        it.description.isNotBlank() && runCatching { ReceiptMoney.decimal(it.totalPrice) }.isSuccess
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.receipt_review_help), style = MaterialTheme.typography.bodyMedium) }
        item {
            OutlinedTextField(
                value = draft.merchant,
                onValueChange = { onChange(draft.copy(merchant = it)) },
                label = { Text(stringResource(R.string.receipt_merchant)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = draft.dateTime.orEmpty(),
                onValueChange = { onChange(draft.copy(dateTime = it.ifBlank { null })) },
                label = { Text(stringResource(R.string.receipt_date)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = draft.total.orEmpty(),
                onValueChange = { onChange(draft.copy(total = it.ifBlank { null })) },
                label = { Text(stringResource(R.string.receipt_total)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            TextButton(onClick = { accountPicker = true }) {
                Text(
                    stringResource(R.string.account) + ": " +
                        (accounts.firstOrNull { it.id == draft.accountId }?.name
                            ?: stringResource(R.string.default_account))
                )
            }
        }
        item { HorizontalDivider() }
        item { Text(stringResource(R.string.receipt_products), style = MaterialTheme.typography.titleMedium) }

        itemsIndexed(draft.lines) { index, line ->
            val matched = products.firstOrNull { it.id == line.productId }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Checkbox(
                            checked = line.include,
                            onCheckedChange = { checked ->
                                onChange(draft.copy(lines = draft.lines.updated(index, line.copy(include = checked))))
                            },
                        )
                        Text(
                            if (line.confidence < 0.70f) stringResource(R.string.receipt_review_line)
                            else stringResource(R.string.receipt_include_line),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = line.description,
                        onValueChange = { value ->
                            onChange(draft.copy(lines = draft.lines.updated(index, line.copy(description = value, productId = null))))
                        },
                        label = { Text(stringResource(R.string.product_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = line.include,
                    )
                    OutlinedTextField(
                        value = line.totalPrice,
                        onValueChange = { value ->
                            onChange(draft.copy(lines = draft.lines.updated(index, line.copy(totalPrice = value))))
                        },
                        label = { Text(stringResource(R.string.amount)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = line.include,
                    )
                    Text(
                        if (matched != null) stringResource(R.string.receipt_matched_product, matched.name)
                        else stringResource(R.string.receipt_new_product),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(enabled = line.include, onClick = { productPickerLine = index }) {
                        Text(stringResource(R.string.receipt_choose_product))
                    }
                }
            }
        }

        item {
            TextButton(onClick = {
                onChange(
                    draft.copy(
                        lines = draft.lines + ReceiptImportLine(
                            rawDescription = "",
                            description = "",
                            totalPrice = "",
                            confidence = 1f,
                        )
                    )
                )
            }) { Text(stringResource(R.string.receipt_add_line)) }
        }

        if (chatGptEnabled) {
            item { HorizontalDivider() }
            item {
                val chooserTitle = stringResource(R.string.receipt_chatgpt_share_title)
                Button(enabled = !busy && included.isNotEmpty(), onClick = {
                    val prompt = ReceiptChatGptBridge.buildPrompt(draft, products)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, prompt)
                    }
                    context.startActivity(Intent.createChooser(share, chooserTitle))
                    pasteDialog = true
                }) { Text(stringResource(R.string.receipt_chatgpt_improve)) }
            }
            item { Text(stringResource(R.string.receipt_chatgpt_share_help), style = MaterialTheme.typography.bodySmall) }
        }

        item { HorizontalDivider() }
        item {
            Button(enabled = !busy && canSave, onClick = onSave) {
                Text(stringResource(R.string.receipt_import_save))
            }
        }
    }

    if (accountPicker) {
        AlertDialog(
            onDismissRequest = { accountPicker = false },
            title = { Text(stringResource(R.string.account)) },
            text = {
                LazyColumn {
                    item {
                        Text(
                            stringResource(R.string.default_account),
                            Modifier.fillMaxWidth().clickable {
                                onChange(draft.copy(accountId = null)); accountPicker = false
                            }.padding(12.dp),
                        )
                    }
                    items(accounts, key = { it.id }) { account ->
                        Text(
                            "${account.name} (${account.currency})",
                            Modifier.fillMaxWidth().clickable {
                                onChange(draft.copy(accountId = account.id, currency = account.currency)); accountPicker = false
                            }.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { accountPicker = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    productPickerLine?.let { index ->
        val line = draft.lines.getOrNull(index)
        if (line != null) {
            AlertDialog(
                onDismissRequest = { productPickerLine = null },
                title = { Text(stringResource(R.string.receipt_choose_product)) },
                text = {
                    LazyColumn {
                        item {
                            Text(
                                stringResource(R.string.receipt_new_product),
                                Modifier.fillMaxWidth().clickable {
                                    onChange(draft.copy(lines = draft.lines.updated(index, line.copy(productId = null))))
                                    productPickerLine = null
                                }.padding(12.dp),
                            )
                        }
                        items(products, key = { it.id }) { product ->
                            Text(
                                product.name,
                                Modifier.fillMaxWidth().clickable {
                                    onChange(
                                        draft.copy(
                                            lines = draft.lines.updated(
                                                index,
                                                line.copy(description = product.name, productId = product.id),
                                            )
                                        )
                                    )
                                    productPickerLine = null
                                }.padding(12.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { productPickerLine = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    if (pasteDialog) {
        AlertDialog(
            onDismissRequest = { pasteDialog = false },
            title = { Text(stringResource(R.string.receipt_chatgpt_paste_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.receipt_chatgpt_paste_help))
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it; pasteError = false },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pasteError) Text(stringResource(R.string.receipt_chatgpt_invalid), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { ReceiptChatGptBridge.applyResponse(pasteText, draft, products) }
                        .onSuccess { enriched -> onChange(enriched); pasteDialog = false; pasteText = ""; pasteError = false }
                        .onFailure { pasteError = true }
                }) { Text(stringResource(R.string.receipt_chatgpt_apply)) }
            },
            dismissButton = { TextButton(onClick = { pasteDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> = mapIndexed { current, item ->
    if (current == index) value else item
}
