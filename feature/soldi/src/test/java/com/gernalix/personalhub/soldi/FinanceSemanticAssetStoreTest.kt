package com.gernalix.personalhub.soldi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FinanceSemanticAssetStoreTest {
    @Test
    fun checksumMismatchInvalidatesCachedAsset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FinanceSemanticAssetStore(context)
        val file = File(context.cacheDir, "semantic-checksum-test").apply { writeText("abc") }
        try {
            assertTrue(
                store.isCurrent(
                    file,
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ),
            )
            file.writeText("changed")
            assertFalse(
                store.isCurrent(
                    file,
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ),
            )
        } finally {
            file.delete()
        }
    }
}
