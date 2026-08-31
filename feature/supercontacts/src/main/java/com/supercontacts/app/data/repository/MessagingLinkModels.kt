package com.supercontacts.app.data.repository

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object MessagingPlatform {
    const val WhatsApp = "whatsapp"
    const val Telegram = "telegram"
    const val Signal = "signal"

    val all = listOf(WhatsApp, Telegram, Signal)
}

object MessagingLinkGenerationStatus {
    const val LinkGenerated = "link_generated"
}

object MessagingLinkVerificationStatus {
    const val Unverified = "unverified"
    const val ManuallyConfirmed = "manually_confirmed"
    const val ManuallyRejected = "manually_rejected"
}

data class MessagingLinkCandidate(
    val platform: String,
    val normalizedPhone: String,
    val deepLink: String,
)

data class ContactMessagingLink(
    val id: Long,
    val contactId: Long,
    val platform: String,
    val normalizedPhone: String,
    val deepLink: String,
    val generationStatus: String,
    val verificationStatus: String,
    val lastScanAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isManuallyVerified: Boolean
        get() = verificationStatus == MessagingLinkVerificationStatus.ManuallyConfirmed
}

object MessagingPhoneNormalizer {
    fun normalizeInternationalDigits(value: String): String? {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return null

        val digits = when {
            cleaned.startsWith("+") -> cleaned.drop(1).filter(Char::isDigit)
            cleaned.startsWith("00") -> cleaned.drop(2).filter(Char::isDigit)
            else -> return null
        }
        return digits.takeIf { it.length in 8..15 }
    }
}

object MessagingDeepLinkGenerator {
    fun generate(phoneValues: List<String>): List<MessagingLinkCandidate> =
        phoneValues
            .mapNotNull(MessagingPhoneNormalizer::normalizeInternationalDigits)
            .distinct()
            .flatMap { normalizedPhone ->
                MessagingPlatform.all.map { platform ->
                    MessagingLinkCandidate(
                        platform = platform,
                        normalizedPhone = normalizedPhone,
                        deepLink = deepLinkFor(platform, normalizedPhone),
                    )
                }
            }

    fun deepLinkFor(platform: String, normalizedPhone: String): String =
        when (platform) {
            MessagingPlatform.WhatsApp -> "https://wa.me/$normalizedPhone"
            MessagingPlatform.Telegram -> "tg://resolve?phone=${urlEncode(normalizedPhone)}"
            // Signal phone links are best-effort contact links and do not prove registration.
            MessagingPlatform.Signal -> "https://signal.me/#p/+${urlEncode(normalizedPhone)}"
            else -> error("Unsupported messaging platform: $platform")
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
