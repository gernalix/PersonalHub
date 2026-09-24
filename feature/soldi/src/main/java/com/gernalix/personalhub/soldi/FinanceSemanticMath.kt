package com.gernalix.personalhub.soldi

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal data class FinanceSemanticMatch(
    val transactionId: Long,
    val attachmentId: String,
    val score: Float,
)

internal object FinanceSemanticMath {
    fun normalize(values: FloatArray): FloatArray {
        val norm = sqrt(values.fold(0.0) { acc, value -> acc + value * value }).toFloat()
        if (norm <= 0f) return values.copyOf()
        return FloatArray(values.size) { index -> values[index] / norm }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size && left.isNotEmpty())
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b
            leftNorm += a * a
            rightNorm += b * b
        }
        val denominator = sqrt(leftNorm) * sqrt(rightNorm)
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    fun encode(values: FloatArray): ByteArray =
        ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> values.forEach(buffer::putFloat) }
            .array()

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % Float.SIZE_BYTES == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }
}
