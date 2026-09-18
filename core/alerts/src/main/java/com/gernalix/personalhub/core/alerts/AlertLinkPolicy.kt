package com.gernalix.personalhub.core.alerts

import android.net.Uri

/**
 * Only an alert whose entire trimmed message is one safe URI gets direct-link notification behavior.
 * Arbitrary Android URI schemes (intent:, file:, content:, etc.) are deliberately rejected.
 */
object AlertLinkPolicy {
    private val allowedSchemes = setOf("http", "https", "workflowy")

    fun linkOnlyUriOrNull(message: String): Uri? {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf(allowedSchemes::contains) ?: return null
        if (!uri.isAbsolute || uri.schemeSpecificPart.isNullOrBlank()) return null
        if ((scheme == "http" || scheme == "https") && uri.host.isNullOrBlank()) return null
        return uri
    }
}
