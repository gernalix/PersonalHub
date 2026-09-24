package com.gernalix.personalhub.soldi

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.gernalix.personalhub.core.database.HubPhotoMediaStore
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAttachment
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule
import com.gernalix.personalhub.core.database.capsules.soldi.FinancePhotoIndex
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal object FinanceSemanticModelSpec {
    const val MODEL_ID = "onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX"
    const val MODEL_VERSION = "9463a9c508a344c837ffefe9d724f3827bf2dc79"
    const val LICENSE = "MIT"
    const val MODEL_SHA256 = "844d1a46ab18acf50c989e541b12fe3b6dc7f8d6004725b4e992d142788e0600"
    const val TOKENIZER_SHA256 = "6d9109cc838977f3ca94a379eec36aecc7c807e1785cd729660ca2fc0171fb35"
    const val MODEL_SIZE_BYTES = 24_683_626L
    const val LICENSE_NOTICE = """MIT License

Copyright (c) Microsoft Corporation.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""
    private const val BASE =
        "https://huggingface.co/onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX/resolve/" + MODEL_VERSION

    val assets = listOf(
        Asset("model_int8.onnx", BASE + "/onnx/model_int8.onnx?download=true", MODEL_SHA256),
        Asset("tokenizer.json", BASE + "/tokenizer.json?download=true", TOKENIZER_SHA256),
    )

    data class Asset(val name: String, val url: String, val sha256: String)
}

internal data class FinanceSemanticFiles(val model: File, val tokenizer: File)

internal class FinanceSemanticAssetStore(private val context: Context) {
    private val mutex = Mutex()
    private val root = File(context.filesDir, "finance-semantic")

    suspend fun ensure(): FinanceSemanticFiles = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = File(root, FinanceSemanticModelSpec.MODEL_VERSION).apply { mkdirs() }
            FinanceSemanticModelSpec.assets.forEach { asset ->
                val target = File(dir, asset.name)
                if (!target.isFile || sha256(target) != asset.sha256) {
                    val temporary = File(dir, asset.name + ".download")
                    temporary.delete()
                    download(asset.url, temporary)
                    check(sha256(temporary) == asset.sha256) { "Il modello foto scaricato non supera il controllo di integrità." }
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                        temporary.delete()
                    }
                }
            }
            File(dir, "LICENSE-TinyCLIP.txt").writeText(FinanceSemanticModelSpec.LICENSE_NOTICE)
            File(dir, "MODEL.txt").writeText(
                listOf(
                    "id=" + FinanceSemanticModelSpec.MODEL_ID,
                    "revision=" + FinanceSemanticModelSpec.MODEL_VERSION,
                    "license=" + FinanceSemanticModelSpec.LICENSE,
                    "model_sha256=" + FinanceSemanticModelSpec.MODEL_SHA256,
                    "tokenizer_sha256=" + FinanceSemanticModelSpec.TOKENIZER_SHA256,
                ).joinToString("\n") + "\n",
            )
            root.listFiles()
                ?.filter { it.isDirectory && it.name != FinanceSemanticModelSpec.MODEL_VERSION }
                ?.forEach(File::deleteRecursively)
            FinanceSemanticFiles(File(dir, "model_int8.onnx"), File(dir, "tokenizer.json"))
        }
    }

    internal fun isCurrent(file: File, expectedSha256: String): Boolean =
        file.isFile && sha256(file) == expectedSha256

    private fun download(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "PersonalHub/" + FinanceSemanticModelSpec.MODEL_VERSION.take(8))
        try {
            check(connection.responseCode in 200..299) {
                "Download modello foto non disponibile (" + connection.responseCode + ")."
            }
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}

private class TinyClipRuntime(files: FinanceSemanticFiles) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(2)
    }
    private val session = environment.createSession(files.model.absolutePath, options)
    private val tokenizer = ClipBpeTokenizer.fromJson(files.tokenizer.readText())

    fun imageEmbedding(bitmap: Bitmap): FloatArray =
        run(text = "", pixels = preprocess(bitmap), outputName = "image_embeds")

    fun textEmbedding(text: String): FloatArray =
        run(text = text, pixels = FloatArray(3 * 224 * 224), outputName = "text_embeds")

    private fun run(text: String, pixels: FloatArray, outputName: String): FloatArray {
        val ids = tokenizer.encode(text)
        val attention = LongArray(ids.size) { 1L }
        val idTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(ids),
            longArrayOf(1, ids.size.toLong()),
        )
        val attentionTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(attention),
            longArrayOf(1, attention.size.toLong()),
        )
        val pixelTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(pixels),
            longArrayOf(1, 3, 224, 224),
        )
        try {
            session.run(
                mapOf(
                    "input_ids" to idTensor,
                    "attention_mask" to attentionTensor,
                    "pixel_values" to pixelTensor,
                ),
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result.get(outputName).orElseThrow().value as Array<FloatArray>
                return FinanceSemanticMath.normalize(output[0])
            }
        } finally {
            idTensor.close()
            attentionTensor.close()
            pixelTensor.close()
        }
    }

    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        private val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        fun preprocess(source: Bitmap): FloatArray {
            require(source.width > 0 && source.height > 0)
            val scale = 224f / minOf(source.width, source.height)
            val width = (source.width * scale).roundToInt().coerceAtLeast(224)
            val height = (source.height * scale).roundToInt().coerceAtLeast(224)
            val resized = Bitmap.createScaledBitmap(source, width, height, true)
            val left = ((width - 224) / 2).coerceAtLeast(0)
            val top = ((height - 224) / 2).coerceAtLeast(0)
            val crop = Bitmap.createBitmap(resized, left, top, 224, 224)
            val pixels = IntArray(224 * 224)
            crop.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            val output = FloatArray(3 * 224 * 224)
            pixels.indices.forEach { index ->
                val pixel = pixels[index]
                val red = ((pixel shr 16) and 0xff) / 255f
                val green = ((pixel shr 8) and 0xff) / 255f
                val blue = (pixel and 0xff) / 255f
                output[index] = (red - mean[0]) / std[0]
                output[224 * 224 + index] = (green - mean[1]) / std[1]
                output[2 * 224 * 224 + index] = (blue - mean[2]) / std[2]
            }
            if (crop !== resized) crop.recycle()
            if (resized !== source) resized.recycle()
            return output
        }
    }
}

/**
 * Local photo index/search. Original photo bytes never enter SQLite; only a compact normalized
 * embedding plus optional OCR text is persisted.
 */
internal class FinancePhotoSemanticEngine(
    context: Context,
    private val capsule: FinanceCapsule,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val assets = FinanceSemanticAssetStore(appContext)
    private val runtimeMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val indexMutex = Mutex()
    @Volatile private var runtime: TinyClipRuntime? = null

    suspend fun backfill(attachments: List<FinanceAttachment>) = indexMutex.withLock {
        val photos = attachments.filter(FinanceAttachment::isDisplayPhoto)
        if (photos.isEmpty()) return@withLock
        for (attachment in photos) {
            val current = capsule.photoIndex(attachment.id)
            if (
                current?.status == STATUS_READY &&
                current.modelId == FinanceSemanticModelSpec.MODEL_ID &&
                current.modelVersion == FinanceSemanticModelSpec.MODEL_VERSION &&
                current.sourceRef == attachment.uri &&
                current.embedding != null
            ) {
                continue
            }
            indexAttachment(attachment, current)
        }
    }

    suspend fun searchText(
        query: String,
        indexes: List<FinancePhotoIndex>,
        limit: Int = 40,
    ): List<FinanceSemanticMatch> {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return emptyList()
        val vector = inferenceMutex.withLock { model().textEmbedding(cleaned) }
        return rank(vector, indexes, TEXT_MIN_SCORE, limit)
    }

    suspend fun findSimilar(
        uri: Uri,
        indexes: List<FinancePhotoIndex>,
        limit: Int = 10,
    ): List<FinanceSemanticMatch> {
        val bitmap = decode(readBytes(uri.toString()))
        return try {
            findSimilar(bitmap, indexes, limit)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun findSimilar(
        bitmap: Bitmap,
        indexes: List<FinancePhotoIndex>,
        limit: Int = 10,
    ): List<FinanceSemanticMatch> {
        val vector = inferenceMutex.withLock { model().imageEmbedding(bitmap) }
        return rank(vector, indexes, IMAGE_MIN_SCORE, limit)
    }

    private suspend fun indexAttachment(
        attachment: FinanceAttachment,
        prior: FinancePhotoIndex?,
    ) {
        val now = System.currentTimeMillis()
        var hash = prior?.sourceHash.orEmpty()
        var bitmap: Bitmap? = null
        try {
            val bytes = readBytes(attachment.uri)
            hash = HubPhotoMediaStore.sha256(bytes)
            if (
                prior?.status == STATUS_READY &&
                prior.modelId == FinanceSemanticModelSpec.MODEL_ID &&
                prior.modelVersion == FinanceSemanticModelSpec.MODEL_VERSION &&
                prior.sourceHash == hash &&
                prior.embedding != null
            ) {
                return
            }
            capsule.putPhotoIndex(
                FinancePhotoIndex(
                    attachmentId = attachment.id,
                    transactionId = attachment.transactionId,
                    sourceRef = attachment.uri,
                    sourceHash = hash,
                    modelId = FinanceSemanticModelSpec.MODEL_ID,
                    modelVersion = FinanceSemanticModelSpec.MODEL_VERSION,
                    status = STATUS_INDEXING,
                    createdAt = prior?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            bitmap = decode(bytes)
            val embedding = inferenceMutex.withLock { model().imageEmbedding(bitmap) }
            val ocrText = runCatching { recognizeText(bitmap) }.getOrDefault("")
            capsule.putPhotoIndex(
                FinancePhotoIndex(
                    attachmentId = attachment.id,
                    transactionId = attachment.transactionId,
                    sourceRef = attachment.uri,
                    sourceHash = hash,
                    modelId = FinanceSemanticModelSpec.MODEL_ID,
                    modelVersion = FinanceSemanticModelSpec.MODEL_VERSION,
                    embedding = FinanceSemanticMath.encode(embedding),
                    ocrText = ocrText,
                    status = STATUS_READY,
                    createdAt = prior?.createdAt ?: now,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } catch (error: Exception) {
            capsule.putPhotoIndex(
                FinancePhotoIndex(
                    attachmentId = attachment.id,
                    transactionId = attachment.transactionId,
                    sourceRef = attachment.uri,
                    sourceHash = hash,
                    modelId = FinanceSemanticModelSpec.MODEL_ID,
                    modelVersion = FinanceSemanticModelSpec.MODEL_VERSION,
                    status = STATUS_ERROR,
                    error = (error.message ?: error::class.java.simpleName).take(240),
                    createdAt = prior?.createdAt ?: now,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            bitmap?.recycle()
        }
    }

    private fun rank(
        vector: FloatArray,
        indexes: List<FinancePhotoIndex>,
        minScore: Float,
        limit: Int,
    ): List<FinanceSemanticMatch> =
        indexes.asSequence()
            .filter {
                it.status == STATUS_READY &&
                    it.modelId == FinanceSemanticModelSpec.MODEL_ID &&
                    it.modelVersion == FinanceSemanticModelSpec.MODEL_VERSION &&
                    it.embedding != null
            }
            .mapNotNull { index ->
                val stored = runCatching { FinanceSemanticMath.decode(requireNotNull(index.embedding)) }.getOrNull()
                    ?: return@mapNotNull null
                if (stored.size != vector.size) return@mapNotNull null
                FinanceSemanticMatch(
                    transactionId = index.transactionId,
                    attachmentId = index.attachmentId,
                    score = FinanceSemanticMath.cosine(vector, stored),
                )
            }
            .filter { it.score >= minScore }
            .sortedByDescending(FinanceSemanticMatch::score)
            .take(limit)
            .toList()

    private suspend fun model(): TinyClipRuntime {
        runtime?.let { return it }
        return runtimeMutex.withLock {
            runtime ?: TinyClipRuntime(assets.ensure()).also { runtime = it }
        }
    }

    private suspend fun readBytes(reference: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = Uri.parse(reference)
        val stream = when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val connection = URL(reference).openConnection()
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.getInputStream()
            }
            else -> appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Foto non leggibile.")
        }
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_IMAGE_BYTES) { "Foto troppo grande per l'indicizzazione." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap =
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Foto non decodificabile." }

    private suspend fun recognizeText(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        if (continuation.isActive) continuation.resume(text.text.trim())
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        } finally {
            recognizer.close()
        }
    }

    override fun close() {
        runtime?.close()
        runtime = null
    }

    companion object {
        const val STATUS_INDEXING = "INDEXING"
        const val STATUS_READY = "READY"
        const val STATUS_ERROR = "ERROR"
        private const val TEXT_MIN_SCORE = 0.24f
        private const val IMAGE_MIN_SCORE = 0.70f
        private const val MAX_IMAGE_BYTES = 40 * 1024 * 1024
    }
}
