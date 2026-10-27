package com.gernalix.personalhub.soldi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceBitmapSamplingTest {
    @Test fun limitsExpandedPixelsAndExtremePanoramas() {
        assertEquals(1, FinanceBitmapSampling.sampleSize(2048, 2048))
        assertEquals(2, FinanceBitmapSampling.sampleSize(4000, 3000))
        assertEquals(4, FinanceBitmapSampling.sampleSize(8192, 8192))
        val panorama = FinanceBitmapSampling.sampleSize(100_000, 2)
        assertTrue(100_000 / panorama <= FinanceBitmapSampling.MAX_SIDE)
        assertTrue((100_000L / panorama) * (2 / panorama) <= FinanceBitmapSampling.MAX_PIXELS)
    }

    @Test fun invalidBitmapDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FinanceBitmapSampling.sampleSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { FinanceBitmapSampling.sampleSize(100, -1) }
    }
}
