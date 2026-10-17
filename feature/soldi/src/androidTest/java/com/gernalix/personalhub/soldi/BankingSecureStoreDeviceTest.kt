package com.gernalix.personalhub.soldi

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BankingSecureStoreDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = BankingSecureStore(context)

    @Before fun reset() = store.disconnect()
    @After fun cleanup() = store.disconnect()

    @Test fun credentialAndCacheUseAndroidKeystoreAndDisconnectDeletesBoth() {
        val token = "synthetic-read-only-token-000000000001"
        store.saveConfiguration(BankingSecureStore.DEFAULT_BASE_URL, token)
        assertEquals(token, store.readCredential())
        assertTrue(store.configuration().configured)

        val snapshot = BankingSnapshot(
            accounts = listOf(BankingAccount("0123456789abcdef01234567", "Fixture Bank", listOf("EUR"), listOf("ITAV"))),
            balances = emptyList(), transactions = emptyList(),
            cache = BankingCacheInfo(Instant.parse("2026-10-17T10:00:00Z"), 0, false, true, null, null),
        )
        store.saveCache(snapshot)
        assertEquals(snapshot, store.readCache())

        store.disconnect()
        assertFalse(store.configuration().configured)
        assertNull(store.readCache())
        assertThrows(IllegalStateException::class.java) { store.readCredential() }
    }
}
