package com.gernalix.personalhub.soldi

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Base64

class GmailReceiptsTestActivity : ComponentActivity()
class GmailReceiptsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<GmailReceiptsTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun isolatedEmulatorOnly() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk") || Build.HARDWARE.contains("ranchu"))
        check(context.packageName == "com.gernalix.personalhub.soldi.test")
        GmailReceiptStore(context).update { GmailCatalog() }
    }
    private fun message() = JSONObject("""{"id":"fixture","internalDate":"${Instant.now().toEpochMilli()}","payload":{"mimeType":"text/plain","headers":[{"name":"Subject","value":"Order receipt"}],"body":{"data":"${Base64.getUrlEncoder().withoutPadding().encodeToString("Merchant: QA Shop\nDate: 2026-11-05\nTotal: 9.99 EUR".toByteArray())}"}}}""")
    @Test fun incrementalSyncIsAtomicIdempotentAndUsesMainDatabase() = runBlocking {
        val store = GmailReceiptStore(context)
        var phase = 0
        val requested = mutableListOf<String>()
        val transport = object : GmailTransport {
            override fun get(path: String, query: Map<String, String>): JSONObject {
                requested += path
                return when(path) {
                    "profile" -> JSONObject("""{"emailAddress":"qa@example.invalid","historyId":"100"}""")
                    "messages" -> JSONObject("""{"messages":[{"id":"fixture"}]}""")
                    "messages/fixture" -> if (phase == 1) throw GmailApiFailure(503) else message()
                    "history" -> {
                        assertEquals(if (phase == 3) "101" else "100", query["startHistoryId"])
                        if (phase == 3) throw GmailApiFailure(404)
                        JSONObject("""{"history":[{"messages":[{"id":"fixture"}]}],"historyId":"101"}""")
                    }
                    else -> error("Unexpected path")
                }
            }
        }
        val db = com.gernalix.personalhub.core.database.PersonalHubDatabase.get(context)
        fun count() = db.openHelper.readableDatabase.query("SELECT count(*) FROM finance_transactions").use { it.moveToFirst(); it.getInt(0) }
        val beforeCount = count()
        GmailReceiptClient(store, transport).sync()
        assertEquals("100", store.read().history); assertEquals(1, store.read().receipts.size)
        phase = 1
        val saved = store.read()
        assertTrue(runCatching { GmailReceiptClient(store, transport).sync() }.isFailure)
        assertEquals(saved, store.read())
        phase = 2; requested.clear()
        GmailReceiptClient(store, transport).sync()
        assertFalse("messages" in requested); assertEquals("101", store.read().history)
        assertEquals(1, GmailReceiptStore(context).read().receipts.size)
        phase = 3
        GmailReceiptClient(store, transport).sync()
        assertEquals("100", store.read().history)
        assertEquals(beforeCount, count())
        db.openHelper.readableDatabase.query("SELECT json FROM hub_preferences WHERE namespace='soldi.gmail.v1'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("qa@example.invalid"))
        }
    }
    @Test fun uiShowsSourceAndExclusionPersistsWithoutDeletingBankData() {
        val store = GmailReceiptStore(context)
        val receipt = requireNotNull(GmailReceiptParser.parse(message()))
        store.update { it.copy(account = "qa@example.invalid", receipts = listOf(receipt)) }
        compose.setContent { SoldiTheme { GmailReceiptsScreen(emptyList(), emptyList()) } }
        compose.onNodeWithText("Gmail e riconciliazione").assertIsDisplayed()
        compose.onNodeWithText("QA Shop").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Apri fonte Gmail").performScrollTo().assertIsDisplayed()
        val screenshot = java.io.File(requireNotNull(context.getExternalFilesDir(null)), "gmail-reconciliation.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Escludi ricevuta").performScrollTo().performClick()
        compose.waitUntil(10000) { "fixture" in store.read().excluded }
        assertEquals(1, store.read().receipts.size)
        compose.onNodeWithText("Ripristina ricevute escluse").performScrollTo().performClick()
        compose.waitUntil(10000) { store.read().excluded.isEmpty() }
    }
    @Test fun pdfInvoiceUsesLocalOcrAndDoesNotPersistUntilReview() = runBlocking {
        val document = android.graphics.pdf.PdfDocument()
        val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 800, 1).create())
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 22f; isAntiAlias = true }
        listOf("Merchant: QA Shop", "Date: 2026-11-05", "Total: 9.99 EUR", "Invoice: QA-1").forEachIndexed { i, line ->
            page.canvas.drawText(line, 30f, 60f + i * 40, paint)
        }
        document.finishPage(page)
        val output = java.io.ByteArrayOutputStream()
        document.writeTo(output); document.close()
        val bytes = output.toByteArray()
        val receipt = requireNotNull(GmailReceiptParser.parse(message()))
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val fake = object : GmailTransport {
            override fun get(path: String, query: Map<String,String>): JSONObject {
                assertEquals("messages/fixture", path)
                return JSONObject("""{"payload":{"mimeType":"multipart/mixed","parts":[{"mimeType":"application/pdf","body":{"size":${bytes.size},"data":"$data"}}]}}""")
            }
        }
        val store = GmailReceiptStore(context)
        val before = store.read()
        val filesBefore = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("invoice-") }.map { it.name }.toSet()
        val result = readGmailInvoicePdf(context, receipt.copy(merchant = null, date = null, amount = null, currency = null), fake)
        assertEquals(java.math.BigDecimal("9.99"), result.amount)
        assertEquals("EUR", result.currency)
        assertEquals(java.time.LocalDate.parse("2026-11-05"), result.date)
        assertEquals(before, store.read())
        assertEquals(filesBefore, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("invoice-") }.map { it.name }.toSet())
    }

    @Test fun persistentTripleMatchRevalidatesSqliteAndReservesBothSides() = runBlocking {
        val db = com.gernalix.personalhub.core.database.PersonalHubDatabase.get(context)
        val dao = db.financeDao()
        val secure = BankingSecureStore(context)
        secure.disconnect()
        val local = com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount(name = "QA account", currency = "EUR")
        dao.add(local)
        val now = Instant.now()
        val transaction = com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransaction(accountId = local.id,
            titleId = null, productId = null, amount = "-9.99", currency = "EUR", chainId = null, placeId = null,
            notes = "", occurredAt = now.toEpochMilli(), createdAt = now.toEpochMilli(), updatedAt = now.toEpochMilli())
        val id = dao.add(transaction)
        val saved = transaction.copy(id = id)
        val rows = listOf(com.gernalix.personalhub.core.database.capsules.soldi.TransactionView(saved, "QA Shop", null, null))
        val bankAccount = BankingAccount("qa-bank", "Fixture", listOf("EUR"), emptyList())
        val movement = BankingTransaction("charge", bankAccount.id, bankAccount.bank, java.math.BigDecimal("-9.99"), "EUR", "DBIT", "BOOK", java.time.LocalDate.now(), "QA Shop")
        secure.saveCache(BankingSnapshot(listOf(bankAccount), emptyList(), listOf(movement, movement.copy(id = "other")), BankingCacheInfo(now, 0, false, true, null, null)))
        val repository = BankingRepository(context)
        try {
            repository.linkAccount(bankAccount, local)
            dao.update(saved.copy(amount = "-10.99"))
            assertTrue(runCatching { repository.confirmLocalMatch("qa-bank:charge", saved.uuid, listOf(local), rows) }.isFailure)
            assertTrue(repository.reconciliationState().matchedTransactions.isEmpty())
            dao.update(saved)
            repository.confirmLocalMatch("qa-bank:charge", saved.uuid, listOf(local), rows)
            assertTrue(runCatching { repository.confirmLocalMatch("qa-bank:other", saved.uuid, listOf(local), rows) }.isFailure)
            assertEquals(saved.uuid, BankingRepository(context).reconciliationState().matchedTransactions["qa-bank:charge"])
            val receipt = requireNotNull(GmailReceiptParser.parse(message())).copy(date = java.time.LocalDate.now())
            val store = GmailReceiptStore(context)
            store.update { it.copy(receipts = listOf(receipt, receipt.copy(messageId = "second"))) }
            store.link(ReceiptBankMatch("fixture", "qa-bank:charge")) { repository.cached() }
            assertTrue(runCatching { store.link(ReceiptBankMatch("second", "qa-bank:charge")) { repository.cached() } }.isFailure)
            assertEquals("qa-bank:charge", GmailReceiptStore(context).read().links["fixture"])
        } finally {
            secure.disconnect()
            dao.deleteTransaction(id)
            db.openHelper.writableDatabase.execSQL("DELETE FROM finance_accounts WHERE id=?", arrayOf(local.id))
        }
    }

}
