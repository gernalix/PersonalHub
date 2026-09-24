package com.gernalix.personalhub.soldi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceSemanticMathTest {
    @Test
    fun embeddingRoundTripAndCosineRankingAreStable() {
        val original = floatArrayOf(0.1f, -0.2f, 0.3f, 0.4f)
        val decoded = FinanceSemanticMath.decode(FinanceSemanticMath.encode(original))
        assertArrayEquals(original, decoded, 0f)

        val query = FinanceSemanticMath.normalize(floatArrayOf(1f, 0f, 0f))
        val near = FinanceSemanticMath.normalize(floatArrayOf(0.95f, 0.1f, 0f))
        val far = FinanceSemanticMath.normalize(floatArrayOf(0.1f, 0.9f, 0f))
        assertTrue(FinanceSemanticMath.cosine(query, near) > FinanceSemanticMath.cosine(query, far))
        assertEquals(1f, FinanceSemanticMath.cosine(query, query), 0.0001f)
    }
}
