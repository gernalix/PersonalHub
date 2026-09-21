package com.gernalix.personalhub.soldi

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAttachment

/**
 * Photo attachments keep only an external reference in SQLite. The original can
 * live behind a persisted SAF content URI or a remote URL; Coil handles
 * downsampling plus memory/disk caching for the small ledger preview.
 */
internal fun FinanceAttachment.isDisplayPhoto(): Boolean =
    kind == "PHOTO_URI" ||
        kind == "PHOTO_URL" ||
        mimeType?.startsWith("image/", ignoreCase = true) == true

internal fun preferredTransactionPhoto(attachments: List<FinanceAttachment>): FinanceAttachment? =
    attachments.firstOrNull { it.isDisplayPhoto() }

@Composable
internal fun FinancePhotoThumbnail(
    attachment: FinanceAttachment,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    FinancePhotoThumbnail(
        uri = attachment.uri,
        title = attachment.title,
        size = size,
        modifier = modifier,
    )
}

@Composable
internal fun FinancePhotoThumbnail(
    uri: String,
    title: String = "",
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = title.takeIf(String::isNotBlank) ?: "Foto transazione",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
