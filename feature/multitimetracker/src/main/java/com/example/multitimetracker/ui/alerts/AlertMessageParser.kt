package com.example.multitimetracker.ui.alerts

import java.net.URI
import com.gernalix.personalhub.core.alerts.AlertLinkPolicy

sealed interface AlertMessageSegment {
    data class Text(val value: String) : AlertMessageSegment
    data class Link(val label: String, val url: String) : AlertMessageSegment
}

fun parseAlertMessageSegments(message: String): List<AlertMessageSegment> {
    if (message.isEmpty()) return emptyList()
    val trimmed = message.trim()
    if (isAllowedAlertLink(trimmed)) {
        return listOf(AlertMessageSegment.Link(abbreviateNakedUrl(trimmed), trimmed))
    }

    val segments = mutableListOf<AlertMessageSegment>()
    var index = 0
    while (index < message.length) {
        val markdownStart = message.indexOf('[', startIndex = index)
        val urlStart = findNextNakedUrlStart(message, index)
        val nextStart = listOf(markdownStart, urlStart).filter { it >= 0 }.minOrNull() ?: -1
        if (nextStart < 0) {
            appendTextSegment(segments, message.substring(index))
            break
        }

        if (nextStart > index) {
            appendTextSegment(segments, message.substring(index, nextStart))
        }

        if (markdownStart >= 0 && markdownStart == nextStart) {
            val parsed = parseMarkdownLinkAt(message, markdownStart)
            if (parsed != null) {
                segments += AlertMessageSegment.Link(parsed.label, parsed.url)
                index = parsed.endExclusive
            } else if (looksLikeMalformedMarkdownLink(message, markdownStart)) {
                appendTextSegment(segments, message.substring(markdownStart))
                break
            } else {
                appendTextSegment(segments, message.substring(markdownStart, markdownStart + 1))
                index = markdownStart + 1
            }
        } else {
            val parsed = parseNakedUrlAt(message, urlStart)
            if (parsed != null) {
                segments += AlertMessageSegment.Link(parsed.label, parsed.url)
                index = parsed.endExclusive
            } else {
                appendTextSegment(segments, message.substring(urlStart, urlStart + 1))
                index = urlStart + 1
            }
        }
    }
    return segments.mergeAdjacentText()
}

fun isAllowedAlertLink(url: String): Boolean =
    AlertLinkPolicy.linkOnlyUriOrNull(url) != null

private data class ParsedLink(
    val label: String,
    val url: String,
    val endExclusive: Int
)

private val nakedUrlRegex = Regex("https?://[^\\s\\])<>\"]+", RegexOption.IGNORE_CASE)

private fun findNextNakedUrlStart(message: String, startIndex: Int): Int =
    nakedUrlRegex.find(message, startIndex)?.range?.first ?: -1

private fun parseMarkdownLinkAt(message: String, startIndex: Int): ParsedLink? {
    val labelEnd = message.indexOf("](", startIndex = startIndex + 1)
    if (labelEnd < 0) return null
    val urlEnd = message.indexOf(')', startIndex = labelEnd + 2)
    if (urlEnd < 0) return null

    val label = message.substring(startIndex + 1, labelEnd).trim()
    val rawUrl = message.substring(labelEnd + 2, urlEnd).trim()
    if (label.isBlank() || !isAllowedAlertLink(rawUrl)) return null
    return ParsedLink(label = label, url = rawUrl, endExclusive = urlEnd + 1)
}

private fun looksLikeMalformedMarkdownLink(message: String, startIndex: Int): Boolean =
    message.indexOf("](", startIndex = startIndex + 1) >= 0

private fun parseNakedUrlAt(message: String, startIndex: Int): ParsedLink? {
    val match = nakedUrlRegex.find(message, startIndex)?.takeIf { it.range.first == startIndex } ?: return null
    val raw = match.value.trimTrailingUrlPunctuation()
    if (!isAllowedAlertLink(raw)) return null
    val trimmedChars = match.value.length - raw.length
    return ParsedLink(
        label = abbreviateNakedUrl(raw),
        url = raw,
        endExclusive = match.range.last + 1 - trimmedChars
    )
}

private fun appendTextSegment(segments: MutableList<AlertMessageSegment>, value: String) {
    if (value.isNotEmpty()) segments += AlertMessageSegment.Text(value)
}

private fun List<AlertMessageSegment>.mergeAdjacentText(): List<AlertMessageSegment> {
    val merged = mutableListOf<AlertMessageSegment>()
    for (segment in this) {
        val last = merged.lastOrNull()
        if (segment is AlertMessageSegment.Text && last is AlertMessageSegment.Text) {
            merged[merged.lastIndex] = AlertMessageSegment.Text(last.value + segment.value)
        } else {
            merged += segment
        }
    }
    return merged
}

private fun String.trimTrailingUrlPunctuation(): String =
    trimEnd('.', ',', ';', ':', '!', '?')

private fun abbreviateNakedUrl(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull()
    val scheme = uri?.scheme?.takeIf { it.isNotBlank() }
    if (scheme != null && scheme.lowercase() != "http" && scheme.lowercase() != "https") return scheme

    val host = uri?.host
        ?.removePrefix("www.")
        ?.split('.')
        ?.firstOrNull { it.isNotBlank() }
    if (!host.isNullOrBlank()) return host

    if (!scheme.isNullOrBlank()) return scheme

    return url.trim().take(MAX_NAKED_URL_LABEL_LENGTH)
}

private const val MAX_NAKED_URL_LABEL_LENGTH = 42
