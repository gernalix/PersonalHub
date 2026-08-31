package com.example.multitimetracker.capsules.remotesync

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal object RemoteSyncContract {
    const val DATABASE = "multitimetracker"
    const val TABLE = "mtt_entities"
    const val BATCH_SIZE = 50
    const val MAX_BATCHES_PER_RUN = 10

    const val TAG_ENTITY_TYPE = "tags"

    val localEntityTables = listOf(
        "sessions",
        "quick_event_templates",
        "quick_event_entries",
        "quick_event_template_fields",
        "quick_event_macros",
    )

    val entityTypes = localEntityTables + TAG_ENTITY_TYPE

    fun stableSyncId(deviceId: String, entityType: String, localId: Long): String =
        UUID.nameUUIDFromBytes("mtt:$deviceId:$entityType:$localId".toByteArray(StandardCharsets.UTF_8)).toString()

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    fun validateBaseUrl(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return value
    }

    fun upsertUrl(baseUrl: String): String =
        "${baseUrl.trimEnd('/')}/$DATABASE/$TABLE/-/upsert"

    fun classifyHttpStatus(status: Int): HttpDisposition = when {
        status in 200..299 -> HttpDisposition.SUCCESS
        status == 401 || status == 403 -> HttpDisposition.AUTH_FAILURE
        status == 408 || status == 425 || status == 429 || status >= 500 -> HttpDisposition.RETRY
        else -> HttpDisposition.PERMANENT_FAILURE
    }
}

internal enum class HttpDisposition { SUCCESS, AUTH_FAILURE, RETRY, PERMANENT_FAILURE }

internal data class RemoteEntity(
    val syncId: String,
    val rowJson: String,
    val fingerprint: String,
)
