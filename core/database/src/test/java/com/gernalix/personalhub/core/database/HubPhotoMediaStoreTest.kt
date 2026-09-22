package com.gernalix.personalhub.core.database

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubPhotoMediaStoreTest {
    @Test
    fun stagesExactOriginalBytesWithoutSquareCropOrReencoding() {
        val original = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x7f)

        val staged = HubPhotoMediaStore.stageOriginal(original, "image/png")
        original[0] = 0x55

        assertEquals("image/png", staged.mimeType)
        assertArrayEquals(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x7f), staged.bytes)
        assertArrayEquals(staged.bytes, HubPhotoMediaStore.staged(staged.reference)?.bytes)
    }

    @Test
    fun replacementGetsStableIndependentReferenceAndDiscardOnlyRemovesTarget() {
        val first = HubPhotoMediaStore.stageOriginal(byteArrayOf(1), "image/jpeg")
        val replacement = HubPhotoMediaStore.stageOriginal(byteArrayOf(2), "image/jpeg")

        assertNotEquals(first.reference, replacement.reference)
        HubPhotoMediaStore.discard(first.reference)

        assertNull(HubPhotoMediaStore.staged(first.reference))
        assertArrayEquals(byteArrayOf(2), HubPhotoMediaStore.staged(replacement.reference)?.bytes)
        HubPhotoMediaStore.discard(replacement.reference)
    }
}
