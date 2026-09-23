package com.gernalix.personalhub.soldi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAttachment
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import com.gernalix.personalhub.core.ui.photo.HubPhotoGrid
import com.gernalix.personalhub.core.ui.photo.HubPhotoGridItem
import com.gernalix.personalhub.core.ui.photo.HubSquarePhotoThumbnail
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class SoldiSearchMode { SEARCH, PHOTOS }

internal fun financeTransactionMatches(
    query: String,
    row: TransactionView,
    tags: List<String>,
    account: FinanceAccount?,
    attachments: List<FinanceAttachment>,
): Boolean {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isEmpty()) return true

    val value = row.value
    val searchable = buildList {
        add(row.title)
        add(row.chain.orEmpty())
        add(row.place.orEmpty())
        add(row.person.orEmpty())
        add(account?.name.orEmpty())
        add(account?.currency.orEmpty())
        addAll(tags)

        // Every persisted transaction field participates in search, even when
        // it is intentionally not rendered in the result row.
        add(value.id.toString())
        add(value.accountId)
        add(value.uuid)
        add(value.titleId?.toString().orEmpty())
        add(value.productId?.toString().orEmpty())
        add(value.amount)
        add(value.currency)
        add(value.chainId?.toString().orEmpty())
        add(value.placeId.orEmpty())
        add(value.fromReceipt.toString())
        add(value.notes)
        add(value.occurredAt.toString())
        add(value.createdAt.toString())
        add(value.updatedAt.toString())
        add(value.personId?.toString().orEmpty())
        add(value.macroId.orEmpty())
        add(value.recurrenceId.orEmpty())
        add(value.occurrenceKey.orEmpty())
        add(value.reminderAt?.toString().orEmpty())
        add(row.category)

        add(searchableDate(value.occurredAt))
        value.reminderAt?.let { add(searchableDate(it)) }

        attachments.forEach { attachment ->
            add(attachment.id)
            add(attachment.kind)
            add(attachment.uri)
            add(attachment.title)
            add(attachment.mimeType.orEmpty())
            add(attachment.createdAt.toString())
        }
    }.joinToString("\n").lowercase(Locale.ROOT)

    val normalizedTags = tags.map { it.lowercase(Locale.ROOT) }
    return normalizedQuery.split(Regex("\\s+or\\s+", RegexOption.IGNORE_CASE)).any { group ->
        group.split(Regex("\\s+")).filter(String::isNotBlank).all { token ->
            when {
                token in setOf("untagged", "no:tags", "#none") -> normalizedTags.isEmpty()
                token.startsWith("-#") -> normalizedTags.none { token.removePrefix("-#") in it }
                token.startsWith("#") -> normalizedTags.any { token.removePrefix("#") in it }
                token.startsWith("-") -> token.removePrefix("-").let { it.isNotEmpty() && it !in searchable }
                else -> token in searchable
            }
        }
    }
}

private fun searchableDate(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd dd/MM/yyyy HH:mm")
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
internal fun SoldiSearchScreen(
    mode: SoldiSearchMode,
    query: String,
    onQueryChange: (String) -> Unit,
    rows: List<TransactionView>,
    accounts: List<FinanceAccount>,
    tagsByTransaction: Map<Long, List<String>>,
    attachments: List<FinanceAttachment>,
    onBack: () -> Unit,
    onPhotos: () -> Unit,
    onSearchMode: () -> Unit,
    onOpenTransaction: (TransactionView) -> Unit,
) {
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val attachmentMap = remember(attachments) { attachments.groupBy { it.transactionId } }
    val rowMap = remember(rows) { rows.associateBy { it.value.id } }

    if (mode == SoldiSearchMode.PHOTOS) {
        val photoItems = remember(attachments, rows, query, tagsByTransaction, accounts) {
            attachments
                .asSequence()
                .filter(FinanceAttachment::isDisplayPhoto)
                .mapNotNull { attachment ->
                    rowMap[attachment.transactionId]
                        ?.takeIf { row ->
                            financeTransactionMatches(
                                query,
                                row,
                                tagsByTransaction[row.value.id].orEmpty(),
                                accountMap[row.value.accountId],
                                attachmentMap[row.value.id].orEmpty(),
                            )
                        }
                        ?.let { attachment to it }
                }
                .sortedWith(
                    compareByDescending<Pair<FinanceAttachment, TransactionView>> { it.second.value.occurredAt }
                        .thenByDescending { it.first.createdAt },
                )
                .toList()
        }
        SoldiPhotoOnlyGrid(
            photos = photoItems,
            onBack = onSearchMode,
            onOpenTransaction = onOpenTransaction,
        )
        return
    }

    val results = remember(query, rows, tagsByTransaction, attachments, accounts) {
        rows
            .asSequence()
            .filter { row ->
                financeTransactionMatches(
                    query = query,
                    row = row,
                    tags = tagsByTransaction[row.value.id].orEmpty(),
                    account = accountMap[row.value.accountId],
                    attachments = attachmentMap[row.value.id].orEmpty(),
                )
            }
            .sortedByDescending { it.value.occurredAt }
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹") }
            Text(
                "Cerca",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onPhotos) { Text("📷") }
        }
        HorizontalDivider()
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true,
            label = { Text("Cerca in tutte le transazioni") },
        )
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nessun risultato")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.value.id }) { row ->
                    val photo = preferredTransactionPhoto(attachmentMap[row.value.id].orEmpty())
                    SoldiSearchResultRow(
                        row = row,
                        account = accountMap[row.value.accountId],
                        tags = tagsByTransaction[row.value.id].orEmpty(),
                        photo = photo,
                        onClick = { onOpenTransaction(row) },
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SoldiSearchResultRow(
    row: TransactionView,
    account: FinanceAccount?,
    tags: List<String>,
    photo: FinanceAttachment?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo != null) {
            HubSquarePhotoThumbnail(photo.toHubPhoto(), size = 52.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val metadata = listOfNotNull(
                row.chain?.takeIf(String::isNotBlank),
                row.place?.takeIf(String::isNotBlank),
                account?.name?.takeIf(String::isNotBlank),
                row.category.takeIf(String::isNotBlank),
                tags.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            ).joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(
                    metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                formatDateTime(row.value.occurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            money(BigDecimal(row.value.amount), row.value.currency),
            fontWeight = FontWeight.SemiBold,
            color = amountColor(BigDecimal(row.value.amount)),
        )
    }
}

@Composable
private fun SoldiPhotoOnlyGrid(
    photos: List<Pair<FinanceAttachment, TransactionView>>,
    onBack: () -> Unit,
    onOpenTransaction: (TransactionView) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹") }
        HubPhotoGrid(
            photos = photos.map { (attachment, row) -> HubPhotoGridItem(attachment.toHubPhoto(), row) },
            onOpenOwner = onOpenTransaction,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
