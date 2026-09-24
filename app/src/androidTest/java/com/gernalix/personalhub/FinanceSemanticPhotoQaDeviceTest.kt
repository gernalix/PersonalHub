package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.soldi.FinanceSemanticQaBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceSemanticPhotoQaDeviceTest {
    @Test fun semanticPhotosOwnedItemsAndPersistenceWorkInQaClone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.gernalix.personalhub.qa", context.packageName)

        val result = FinanceSemanticQaBridge.exercise(context)

        assertEquals(2, result.readyIndexes)
        assertEquals(result.textTopTransactionId, result.imageTopTransactionId)
        assertTrue(result.ownedItemPersisted)
        assertTrue(result.nonPhotoExcluded)
    }
}
