package com.supercontacts.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.supercontacts.app.R
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
            val bytes = java.io.ByteArrayOutputStream().also { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, JpegQuality, output))
            }.toByteArray()
            val reference = com.gernalix.personalhub.core.database.PhotoCapsule.stage(bytes)
            if (scaled !== cropped) {
                cropped.recycle()
            }
            if (source !== cropped && source !== scaled) {
                source.recycle()
            }
            reference
        }

    suspend fun deletePhoto(reference: String): Boolean {
        com.gernalix.personalhub.core.database.PhotoCapsule.discard(reference)
        // Committed photo deletion follows its contact/field foreign keys.
        return true
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

    private fun openPhotoInputStream(reference: String): InputStream? {
        val bytes = com.gernalix.personalhub.core.database.PhotoCapsule.preview(reference)
            ?: com.gernalix.personalhub.core.database.PersonalHubDatabase.get(appContext).photoDao().find(reference)?.bytes
        return bytes?.inputStream()
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
