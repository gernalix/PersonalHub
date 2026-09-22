package com.gernalix.luoghi

import com.gernalix.luoghi.data.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacePhotoContractTest {
    @Test
    fun editorRoundTripPreservesCanonicalPhotoReference() {
        val place = PlaceEntity(
            uuid = "place-42",
            nickname = "Home",
            photoUri = "content://photos/home",
        )

        val form = PlaceFormState.from(place)

        assertEquals("content://photos/home", form.photoUri)
        assertEquals("place-42", form.uuid)
    }
}
