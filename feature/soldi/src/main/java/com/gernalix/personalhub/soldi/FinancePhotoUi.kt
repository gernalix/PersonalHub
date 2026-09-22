package com.gernalix.personalhub.soldi

import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAttachment
import com.gernalix.personalhub.core.ui.photo.HubPhoto

/**
 * Finance binds canonical attachment rows to the shared photo engine.
 */
internal fun FinanceAttachment.isDisplayPhoto(): Boolean =
    kind == "PHOTO_URI" ||
        kind == "PHOTO_URL" ||
        mimeType?.startsWith("image/", ignoreCase = true) == true

internal fun preferredTransactionPhoto(attachments: List<FinanceAttachment>): FinanceAttachment? =
    attachments.firstOrNull { it.isDisplayPhoto() }

internal fun FinanceAttachment.toHubPhoto(): HubPhoto = HubPhoto(id, transactionId.toString(), uri, title.takeIf(String::isNotBlank) ?: "Foto transazione")
