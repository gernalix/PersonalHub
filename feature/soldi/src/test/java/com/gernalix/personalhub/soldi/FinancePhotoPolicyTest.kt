package com.gernalix.personalhub.soldi

import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancePhotoPolicyTest {
    @Test
    fun detectsExplicitAndMimeTypedPhotos() {
        assertTrue(attachment(kind = "PHOTO_URI", mime = null).isDisplayPhoto())
        assertTrue(attachment(kind = "PHOTO_URL", mime = null).isDisplayPhoto())
        assertTrue(attachment(kind = "URI", mime = "image/jpeg").isDisplayPhoto())
        assertFalse(attachment(kind = "URI", mime = "application/pdf").isDisplayPhoto())
        assertFalse(attachment(kind = "URL", mime = null).isDisplayPhoto())
    }

    @Test
    fun prefersFirstPhotoWithoutTreatingGenericLinksAsImages() {
        val generic = attachment(kind = "URL", mime = null, id = "generic")
        val photo = attachment(kind = "PHOTO_URL", mime = null, id = "photo")
        assertEquals("photo", preferredTransactionPhoto(listOf(generic, photo))?.id)
    }

    @Test
    fun sharedPhotoBindingKeepsStableTransactionOwner() {
        val photo = attachment(kind = "PHOTO_URI", mime = "image/jpeg", id = "photo-42").toHubPhoto()
        assertEquals("photo-42", photo.id)
        assertEquals("1", photo.ownerId)
        assertEquals("https://example.test/item", photo.reference)
    }

    private fun attachment(kind: String, mime: String?, id: String = "id") = FinanceAttachment(
        id = id,
        transactionId = 1,
        kind = kind,
        uri = "https://example.test/item",
        mimeType = mime,
    )
}
