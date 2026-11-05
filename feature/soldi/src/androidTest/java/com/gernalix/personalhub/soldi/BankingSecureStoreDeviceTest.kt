package com.gernalix.personalhub.soldi

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BankingSecureStoreDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = BankingSecureStore(context)

    @Before fun reset() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk") || android.os.Build.HARDWARE.contains("ranchu"))
        check(context.packageName == "com.gernalix.personalhub.soldi.test")
        store.disconnect()
    }
    @After fun cleanup() = store.disconnect()

    @Test fun credentialUsesKeystoreAndFinanceDataUsesMainSqlite() {
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

        val reconciliation = store.updateReconciliationState {
            BankingReconciliationState(
                accountLinks = mapOf("external-account-fixture" to "local-account-fixture"),
                alertPreferences = BankingAlertPreferences(true, BigDecimal("25.00")),
                acceptedSuggestions = mapOf("external-account-fixture:movement-fixture" to "local-transaction-fixture"),
            )
        }
        assertEquals(reconciliation, store.reconciliationState())
        assertFalse(File(context.noBackupFilesDir, "soldi-banking-reconciliation.enc").exists())
        val saved = com.gernalix.personalhub.core.database.DatabasePreferences(context, "soldi.banking.v1").getString("reconciliation", null)
        assertTrue(requireNotNull(saved).contains("external-account-fixture"))
        assertEquals(reconciliation, BankingSecureStore(context).reconciliationState())

        store.disconnect()
        assertFalse(store.configuration().configured)
        assertNull(store.readCache())
        assertEquals(BankingReconciliationState(), store.reconciliationState())
        assertThrows(IllegalStateException::class.java) { store.readCredential() }
    }
    @Test fun legacyEncryptedStateMovesToMainSqliteOnlyAfterVerification() {
        val alias = "personalhub.soldi.banking.reconciliation"
        val source = """{"account_links":{"old-bank":"local"},"accepted_suggestions":{"old-bank:charge":"transaction"},"observed_alerts":{},"notified_alerts":{}}"""
        val encrypt = BankingSecureStore::class.java.getDeclaredMethod("encrypt", String::class.java, String::class.java).apply { isAccessible = true }
        val file = File(context.noBackupFilesDir, "soldi-banking-reconciliation.enc")
        file.writeBytes(encrypt.invoke(store, source, alias) as ByteArray)
        val migrated = store.reconciliationState()
        assertEquals("local", migrated.accountLinks["old-bank"])
        assertEquals("transaction", migrated.acceptedSuggestions["old-bank:charge"])
        assertFalse(file.exists())
        assertEquals(migrated, BankingSecureStore(context).reconciliationState())
        val database = com.gernalix.personalhub.core.database.PersonalHubDatabase.get(context).openHelper.readableDatabase
        database.query("SELECT json FROM hub_preferences WHERE namespace='soldi.banking.v1'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("old-bank"))
        }
    }

}
