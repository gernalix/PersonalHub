package com.gernalix.personalhub.core.alerts

import android.net.Uri
import java.net.URI

/**
 * Only an alert whose entire trimmed message is one safe URI gets direct-link notification behavior.
 * Arbitrary Android URI schemes (intent:, file:, content:, etc.) are deliberately rejected.
 */
object AlertLinkPolicy {
    private val allowedSchemes = setOf("http", "https", "workflowy")

    fun linkOnlyUriOrNull(message: String): Uri? {
        if (!isLinkOnlyUri(message)) return null
        return Uri.parse(message.trim())
    }

    fun isLinkOnlyUri(message: String): Boolean {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()?.takeIf(allowedSchemes::contains) ?: return false
        if (!uri.isAbsolute || uri.schemeSpecificPart.isNullOrBlank()) return false
        if ((scheme == "http" || scheme == "https") && uri.host.isNullOrBlank()) return false
        return true
    }
}
