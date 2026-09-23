package com.gernalix.personalhub.core.ui.photo

import org.junit.Assert.assertEquals
import org.junit.Test

class HubPhotoContractTest {
    @Test
    fun preservesStableOwnerAndLoaderIdentityAcrossSharedSurfaces() {
        val bytes = byteArrayOf(1, 2, 3)
        val photo = HubPhoto(
            id = "photo-7",
            ownerId = "person-42",
            reference = "hubphoto:7",
            contentDescription = "Photo of Ada",
            loaderData = bytes,
        )

        assertEquals("photo-7", photo.id)
        assertEquals("person-42", photo.ownerId)
        assertEquals("hubphoto:7", photo.reference)
        assertEquals(bytes, photo.loaderData)
    }
}
