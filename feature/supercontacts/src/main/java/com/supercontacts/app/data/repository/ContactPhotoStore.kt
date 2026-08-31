package com.supercontacts.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.supercontacts.app.R
import com.supercontacts.app.data.backup.BackupPreferencesStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactPhotoCropSpec(
    val leftFraction: Float,
    val topFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
)

class ContactPhotoStore(context: Context) {
    private val appContext = context.applicationContext
    private val backupPrefs = BackupPreferencesStore(appContext)
    private val photoResolver = ContactPhotoResolver(appContext)
    private val photoIoLock = Any()

    suspend fun loadPreviewBitmap(uri: Uri, maxSizePx: Int = PreviewMaxSizePx): Bitmap? =
        withContext(Dispatchers.IO) {
            decodeBitmap(uri = uri, maxSizePx = maxSizePx)
        }

    suspend fun saveCroppedPhoto(
        sourceUri: Uri,
        cropSpec: ContactPhotoCropSpec,
        outputSizePx: Int = SavedPhotoSizePx,
        contactId: Long? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val source = decodeBitmap(uri = sourceUri, maxSizePx = DecodeMaxSizePx)
                ?: error("Selected image could not be read.")
            val cropRect = cropSpec.toBitmapCrop(source)
            val cropped = Bitmap.createBitmap(
                source,
                cropRect.left,
                cropRect.top,
                cropRect.size,
                cropRect.size,
            )
            val scaled = if (cropped.width == outputSizePx && cropped.height == outputSizePx) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, outputSizePx, outputSizePx, true)
            }
            val reference = relativePhotoReference(
                fileName = buildPhotoFileName(contactId = contactId),
            )
            synchronized(photoIoLock) {
                writeBitmapToPhoto(reference, scaled)
            }
            if (scaled !== cropped) {
                cropped.recycle()
            }
            if (source !== cropped && source !== scaled) {
                source.recycle()
            }
            reference
        }

    suspend fun migrateLegacyPhotoToSaf(contactId: Long, reference: String): String? =
        withContext(Dispatchers.IO) {
            val newReference = relativePhotoReference(buildPhotoFileName(contactId))
            synchronized(photoIoLock) {
                openLegacyPhotoInputStream(reference)?.use { input ->
                    writeInputStreamToPhoto(newReference, input)
                } ?: return@withContext null
            }
            newReference
        }

    suspend fun deletePhoto(reference: String): Boolean =
        withContext(Dispatchers.IO) {
            if (reference.isBlank()) return@withContext false
            synchronized(photoIoLock) {
                runCatching {
                    if (isRelativePhotoReference(reference)) {
                        deleteRelativePhoto(reference)
                    } else {
                        legacyPhotoFile(reference)?.delete() == true
                    }
                }.onFailure { error ->
                    Log.w(LogTag, "Photo cleanup failed for app-owned reference.", error)
                }.getOrDefault(false).also { deleted ->
                    if (deleted) {
                        Log.i(LogTag, "Photo cleanup deleted app-owned file.")
                    }
                }
            }
        }

    fun loadPhotoBitmap(reference: String, targetSizePx: Int): Bitmap? {
        if (reference.isBlank()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            openPhotoInputStream(reference)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }.onFailure { error ->
            Log.w(LogTag, "Photo bounds decode failed for app-owned reference.", error)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            openPhotoInputStream(reference)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        }.onFailure { error ->
            Log.w(LogTag, "Photo bitmap decode failed for app-owned reference.", error)
        }.getOrNull() ?: return null
        if (decoded.width <= targetSizePx && decoded.height <= targetSizePx) return decoded

        val side = targetSizePx.coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, side, side, true).also { scaled ->
            if (scaled !== decoded) decoded.recycle()
        }
    }

    private fun decodeBitmap(uri: Uri, maxSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null

        val orientation = appContext.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        return decoded.rotateForExif(orientation)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSizePx: Int): Int {
        val largestSide = max(width, height)
        if (largestSide <= maxSizePx) return 1
        val ratio = ceil(largestSide.toDouble() / maxSizePx.toDouble()).roundToInt()
        return Integer.highestOneBit(ratio).coerceAtLeast(1)
    }

    private fun ContactPhotoCropSpec.toBitmapCrop(bitmap: Bitmap): BitmapCrop {
        val left = (leftFraction.coerceIn(0f, 1f) * bitmap.width).roundToInt()
        val top = (topFraction.coerceIn(0f, 1f) * bitmap.height).roundToInt()
        val width = (widthFraction.coerceIn(0.01f, 1f) * bitmap.width).roundToInt()
        val height = (heightFraction.coerceIn(0.01f, 1f) * bitmap.height).roundToInt()
        val size = min(width, height)
            .coerceAtMost(bitmap.width - left)
            .coerceAtMost(bitmap.height - top)
            .coerceAtLeast(1)
        val centeredLeft = left + ((width - size) / 2)
        val centeredTop = top + ((height - size) / 2)
        return BitmapCrop(
            left = centeredLeft.coerceIn(0, bitmap.width - size),
            top = centeredTop.coerceIn(0, bitmap.height - size),
            size = size,
        )
    }

    private fun Bitmap.rotateForExif(orientation: Int): Bitmap =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotate(270f)
            else -> this
        }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply {
            postRotate(degrees)
        }, true)
        if (rotated !== this) {
            recycle()
        }
        return rotated
    }

    private data class BitmapCrop(
        val left: Int,
        val top: Int,
        val size: Int,
    )

    private fun buildPhotoFileName(contactId: Long?): String {
        val owner = contactId?.let { "contact_$it" } ?: "contact_pending"
        return "${owner}_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg"
    }

    private fun relativePhotoReference(fileName: String): String = "$PhotoDirectory/$fileName"

    private fun isRelativePhotoReference(reference: String): Boolean =
        ContactPhotoResolver.isRelativePhotoReference(reference)

    private fun requireBackupRootUri(): Uri {
        val uriString = backupPrefs.readFolderUri()
        if (uriString.isNullOrBlank()) {
            throw IllegalStateException(appContext.getString(R.string.backup_folder_not_configured))
        }
        return Uri.parse(uriString)
    }

    private fun backupRootUriOrNull(): Uri? =
        backupPrefs.readFolderUri()?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    private fun writeBitmapToPhoto(reference: String, bitmap: Bitmap) {
        writeOutputToPhoto(reference) { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JpegQuality, output)
        }
    }

    private fun writeInputStreamToPhoto(reference: String, input: InputStream) {
        writeOutputToPhoto(reference) { output ->
            input.copyTo(output)
        }
    }

    private fun writeOutputToPhoto(
        reference: String,
        write: (java.io.OutputStream) -> Unit,
    ) {
        val rootUri = requireBackupRootUri()
        if (rootUri.scheme == "file") {
            val root = File(requireNotNull(rootUri.path))
            val photosRoot = ContactPhotoResolver.canonicalFilePhotoDirectory(root, create = true)
                ?: throw IllegalStateException(appContext.getString(R.string.backup_export_failed))
            val target = ContactPhotoResolver.resolveFileForWrite(root, reference)
                ?: throw IllegalStateException(appContext.getString(R.string.backup_export_failed))
            require(target.parentFile?.canonicalPath == photosRoot.canonicalPath) {
                appContext.getString(R.string.backup_export_failed)
            }
            FileOutputStream(target).use { output ->
                write(output)
                output.fd.sync()
            }
            return
        }

        val document = photoResolver.resolveDocumentForWrite(
            rootUri = rootUri,
            reference = reference,
        ) ?: throw IllegalStateException(appContext.getString(R.string.backup_export_failed))
        appContext.contentResolver.openOutputStream(document.uri, "rwt").use { output ->
            requireNotNull(output) { appContext.getString(R.string.backup_export_failed) }
            write(output)
        }
    }

    private fun openPhotoInputStream(reference: String): InputStream? {
        if (isRelativePhotoReference(reference)) {
            val rootUri = backupRootUriOrNull() ?: return null
            if (rootUri.scheme == "file") {
                val root = File(rootUri.path ?: return null)
                val file = ContactPhotoResolver.resolveFileForRead(root, reference)
                return if (file?.isFile == true) FileInputStream(file) else null
            }
            val document = photoResolver.resolveDocumentForRead(
                rootUri = rootUri,
                reference = reference,
            ) ?: return null
            return runCatching {
                appContext.contentResolver.openInputStream(document.uri)
            }.onFailure { error ->
                Log.w(LogTag, "Photo document open failed for app-owned reference.", error)
            }.getOrNull()
        }

        val uri = runCatching { Uri.parse(reference) }.getOrNull()
        if (uri?.scheme == "content") {
            return runCatching {
                appContext.contentResolver.openInputStream(uri)
            }.onFailure { error ->
                Log.w(LogTag, "Legacy content photo open failed.", error)
            }.getOrNull()
        }

        val file = File(reference)
        return if (file.isFile) FileInputStream(file) else null
    }

    private fun deleteRelativePhoto(reference: String): Boolean {
        val rootUri = backupRootUriOrNull() ?: return false
        if (rootUri.scheme == "file") {
            val root = File(rootUri.path ?: return false)
            val file = ContactPhotoResolver.resolveFileForDelete(root, reference) ?: return true
            return file.parentFile?.name?.let(ContactPhotoResolver::isPhotoDirectoryName) == true &&
                (!file.exists() || file.delete())
        }
        return photoResolver.resolveDocumentForDelete(
            rootUri = rootUri,
            reference = reference,
        )?.delete() ?: false
    }

    private fun legacyPhotoFile(reference: String): File? {
        if (reference.isBlank()) return null
        val file = File(reference)
        val legacyRoot = File(appContext.filesDir, LegacyPhotoDirectory)
        return if (file.parentFile?.canonicalPath == legacyRoot.canonicalPath) file else null
    }

    private fun openLegacyPhotoInputStream(reference: String): InputStream? {
        legacyPhotoFile(reference)?.takeIf { it.isFile }?.let { return FileInputStream(it) }
        val uri = runCatching { Uri.parse(reference) }.getOrNull()
        return if (uri?.scheme == "content") {
            appContext.contentResolver.openInputStream(uri)
        } else {
            null
        }
    }

    private companion object {
        const val PhotoDirectory = ContactPhotoResolver.PhotoDirectory
        const val LegacyPhotoDirectory = "contact_photos"
        const val PreviewMaxSizePx = 1080
        const val DecodeMaxSizePx = 2048
        const val SavedPhotoSizePx = 512
        const val JpegQuality = 70
        const val LogTag = "ContactPhotoStore"
    }
}
