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
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceOwnedItem
import com.gernalix.personalhub.core.database.capsules.soldi.FinancePhotoIndex
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import com.gernalix.personalhub.core.ui.photo.HubPhotoGrid
import com.gernalix.personalhub.core.ui.photo.HubPhotoGridItem
import com.gernalix.personalhub.core.ui.photo.HubSquarePhotoThumbnail
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class SoldiSearchMode { SEARCH, PHOTOS, OBJECT }

internal fun financeTransactionMatches(
    query: String,
    row: TransactionView,
    tags: List<String>,
    account: FinanceAccount?,
    attachments: List<FinanceAttachment>,
    photoIndexes: List<FinancePhotoIndex> = emptyList(),
    ownedItems: List<FinanceOwnedItem> = emptyList(),
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
        photoIndexes.forEach { index ->
            add(index.ocrText)
            add(index.labels)
        }
        ownedItems.forEach { item ->
            add(item.uuid)
            add(item.name)
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
    photoIndexes: List<FinancePhotoIndex>,
    ownedItems: List<FinanceOwnedItem>,
    semanticMatches: List<FinanceSemanticMatch>,
    objectMatches: List<FinanceSemanticMatch>,
    onBack: () -> Unit,
    onPhotos: () -> Unit,
    onFindObject: () -> Unit,
    onSearchMode: () -> Unit,
    onOpenTransaction: (TransactionView) -> Unit,
) {
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val attachmentMap = remember(attachments) { attachments.groupBy { it.transactionId } }
    val rowMap = remember(rows) { rows.associateBy { it.value.id } }
    val indexMap = remember(photoIndexes) { photoIndexes.groupBy { it.transactionId } }
    val ownedMap = remember(ownedItems) { ownedItems.groupBy { it.sourceTransactionId } }
    val semanticByTransaction = remember(semanticMatches) {
        semanticMatches.groupBy { it.transactionId }.mapValues { (_, values) -> values.maxBy { it.score } }
    }

    if (mode == SoldiSearchMode.OBJECT) {
        SoldiObjectMatches(
            matches = objectMatches,
            attachments = attachments.associateBy { it.id },
            rows = rowMap,
            onBack = onSearchMode,
            onFindObject = onFindObject,
            onOpenTransaction = onOpenTransaction,
        )
        return
    }

    if (mode == SoldiSearchMode.PHOTOS) {
        val semanticAttachmentIds = remember(semanticMatches) { semanticMatches.mapTo(hashSetOf()) { it.attachmentId } }
        val photoItems = remember(attachments, rows, query, tagsByTransaction, accounts, photoIndexes, ownedItems, semanticAttachmentIds) {
            attachments
                .asSequence()
                .filter(FinanceAttachment::isDisplayPhoto)
                .mapNotNull { attachment ->
                    val row = rowMap[attachment.transactionId] ?: return@mapNotNull null
                    val direct = financeTransactionMatches(
                        query,
                        row,
                        tagsByTransaction[row.value.id].orEmpty(),
                        accountMap[row.value.accountId],
                        attachmentMap[row.value.id].orEmpty(),
                        indexMap[row.value.id].orEmpty(),
                        ownedMap[row.value.id].orEmpty(),
                    )
                    val semantic = query.isNotBlank() && attachment.id in semanticAttachmentIds
                    row.takeIf { direct || semantic }?.let { attachment to it }
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
            onFindObject = onFindObject,
            onOpenTransaction = onOpenTransaction,
        )
        return
    }

    val results = remember(query, rows, tagsByTransaction, attachments, accounts, photoIndexes, ownedItems, semanticMatches) {
        rows
            .asSequence()
            .map { row ->
                val direct = financeTransactionMatches(
                    query = query,
                    row = row,
                    tags = tagsByTransaction[row.value.id].orEmpty(),
                    account = accountMap[row.value.accountId],
                    attachments = attachmentMap[row.value.id].orEmpty(),
                    photoIndexes = indexMap[row.value.id].orEmpty(),
                    ownedItems = ownedMap[row.value.id].orEmpty(),
                )
                Triple(row, direct, semanticByTransaction[row.value.id])
            }
            .filter { (_, direct, semantic) -> direct || semantic != null }
            .sortedWith(
                compareByDescending<Triple<TransactionView, Boolean, FinanceSemanticMatch?>> {
                    (if (it.second) 1f else 0f) + (it.third?.score ?: 0f)
                }.thenByDescending { it.first.value.occurredAt },
            )
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
            TextButton(onClick = onFindObject) { Text("Trova oggetto") }
            TextButton(onClick = onPhotos) { Text("📷") }
        }
        HorizontalDivider()
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true,
            label = { Text("Cerca in tutte le transazioni e nelle foto") },
        )
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nessun risultato")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.first.value.id }) { result ->
                    val row = result.first
                    val indexes = indexMap[row.value.id].orEmpty()
                    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
                    val ocrHit = normalizedQuery.isNotEmpty() && indexes.any {
                        normalizedQuery in it.ocrText.lowercase(Locale.ROOT)
                    }
                    val photo = preferredTransactionPhoto(attachmentMap[row.value.id].orEmpty())
                    SoldiSearchResultRow(
                        row = row,
                        account = accountMap[row.value.accountId],
                        tags = tagsByTransaction[row.value.id].orEmpty(),
                        photo = photo,
                        ownedItems = ownedMap[row.value.id].orEmpty(),
                        semanticScore = result.third?.score,
                        ocrHit = ocrHit,
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
    ownedItems: List<FinanceOwnedItem>,
    semanticScore: Float?,
    ocrHit: Boolean,
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
                ownedItems.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.name },
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
            if (ocrHit) {
                Text("Testo trovato nella foto", style = MaterialTheme.typography.bodySmall)
            }
            semanticScore?.let {
                Text(
                    "Corrispondenza visiva " + String.format(Locale.ROOT, "%.2f", it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onFindObject: () -> Unit,
    onOpenTransaction: (TransactionView) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Text("Foto", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onFindObject) { Text("Trova oggetto") }
        }
        HubPhotoGrid(
            photos = photos.map { (attachment, row) -> HubPhotoGridItem(attachment.toHubPhoto(), row) },
            onOpenOwner = onOpenTransaction,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SoldiObjectMatches(
    matches: List<FinanceSemanticMatch>,
    attachments: Map<String, FinanceAttachment>,
    rows: Map<Long, TransactionView>,
    onBack: () -> Unit,
    onFindObject: () -> Unit,
    onOpenTransaction: (TransactionView) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Column(Modifier.weight(1f)) {
                Text("Trova questo oggetto", fontWeight = FontWeight.SemiBold)
                Text("Possibili corrispondenze, non identificazioni certe", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onFindObject) { Text("Nuova foto") }
        }
        HorizontalDivider()
        if (matches.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nessun candidato abbastanza simile")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(matches, key = { it.attachmentId }) { match ->
                    val attachment = attachments[match.attachmentId]
                    val row = rows[match.transactionId]
                    if (attachment != null && row != null) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenTransaction(row) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HubSquarePhotoThumbnail(attachment.toHubPhoto(), size = 56.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Somiglianza " + String.format(Locale.ROOT, "%.2f", match.score),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
