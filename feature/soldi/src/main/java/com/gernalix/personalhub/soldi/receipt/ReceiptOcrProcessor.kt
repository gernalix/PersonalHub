package com.gernalix.personalhub.soldi.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** On-device receipt OCR restored from the former Soldi implementation. */
class ReceiptOcrProcessor {
    suspend fun recognize(context: Context, uri: Uri): String {
        val bitmap = loadBitmap(context, uri)
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        if (continuation.isActive) continuation.resume(text.toReceiptLineText())
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        } finally {
            recognizer.close()
        }
    }

    private suspend fun loadBitmap(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalArgumentException("Receipt image could not be decoded.")
    }

    private fun Text.toReceiptLineText(): String {
        val lines = textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text.trim(), box.top, box.left, box.height())
            }
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })

        if (lines.isEmpty()) return text

        val rowTolerance = (lines.map { it.height }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 24.0) * 0.65
        val rows = mutableListOf<MutableList<OcrLine>>()
        lines.forEach { line ->
            val row = rows.lastOrNull()
            val rowTop = row?.map { it.top }?.average()
            if (row != null && rowTop != null && kotlin.math.abs(line.top - rowTop) <= rowTolerance) {
                row += line
            } else {
                rows += mutableListOf(line)
            }
        }

        return rows
            .flatMap { row -> row.sortedBy { it.left }.map { it.text } }
            .joinToString("\n")
    }

    private data class OcrLine(
        val text: String,
        val top: Int,
        val left: Int,
        val height: Int,
    )
}
