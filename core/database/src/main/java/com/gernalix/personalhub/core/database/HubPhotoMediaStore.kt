package com.gernalix.personalhub.core.database

import android.content.ContentResolver
import android.net.Uri
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HubStagedPhoto(
    val reference: String,
    val mimeType: String,
    val bytes: ByteArray,
    val sha256: String,
)

/** Process-local staging for original image bytes awaiting an owner transaction. */
object HubPhotoMediaStore {
    private val pending = ConcurrentHashMap<String, HubStagedPhoto>()

    fun stageOriginal(bytes: ByteArray, mimeType: String?): HubStagedPhoto {
        require(bytes.isNotEmpty()) { "Selected image is empty." }
        val reference = "hubphoto:${UUID.randomUUID()}"
        return HubStagedPhoto(
            reference = reference,
            mimeType = mimeType?.takeIf(String::isNotBlank) ?: "image/*",
            bytes = bytes.copyOf(),
            sha256 = sha256(bytes),
        ).also { pending[reference] = it }
    }

    suspend fun importOriginal(
        contentResolver: ContentResolver,
        uri: Uri,
        mimeType: String? = contentResolver.getType(uri),
    ): HubStagedPhoto = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Selected image could not be read.")
        stageOriginal(bytes, mimeType)
    }

    fun staged(reference: String): HubStagedPhoto? = pending[reference]

    fun discard(reference: String) {
        pending.remove(reference)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
