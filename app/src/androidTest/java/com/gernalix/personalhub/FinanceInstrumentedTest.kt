package com.gernalix.personalhub

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.*
import com.gernalix.personalhub.core.database.capsules.soldi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only the separate QA installation. Never opens an original Soldi source or real PH sandbox. */
@RunWith(AndroidJUnit4::class)
class FinanceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun guard() {
        check(context.packageName == "com.gernalix.personalhub.qa") { "Only the isolated QA package is allowed" }
        check(DatabaseVault.folder(context)?.contains("PersonalHubSoldiQA") == true) { "Select the isolated SAF test folder" }
    }
    private fun scalar(sql: String) = PersonalHubDatabase.get(context).openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun exportedCount(expected: Long) {
        val expectedGeneration = scalar("SELECT generation FROM hub_generation")
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(DatabaseVault.folder(context)))!!
            val exported = root.findFile("personalhub.db")
            if (exported != null) {
                val local = File(context.cacheDir, "finance-qa-readback.db")
                try {
                    context.contentResolver.openInputStream(exported.uri)!!.use { input -> local.outputStream().use { input.copyTo(it) } }
                    if (DatabaseVault.validate(context, local) == expectedGeneration) {
                        SQLiteDatabase.openDatabase(local.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                            db.rawQuery("SELECT COUNT(*) FROM finance_transactions", null).use { assertTrue(it.moveToFirst()); assertEquals(expected, it.getLong(0)) }
                            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                        }
                        return
                    }
                } catch (_: Exception) { /* A provider rotation is retried until the bounded deadline. */ }
                finally { local.delete() }
            }
            Thread.sleep(300)
        }
        fail("Automatic SAF export did not converge: ${DatabaseVault.error(context)}")
    }
    @Test fun syntheticCrudPersistsAndReachesAutoExportAndSyncJournal() = runBlocking {
        guard()
        val db = PersonalHubDatabase.get(context)
        val finance = FinanceCapsule(db)
        assertEquals(0, scalar("SELECT count(*) FROM finance_transactions"))
        assertEquals(0, scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE '%receipt%'"))
        val id = finance.saveTransaction(TransactionDraft(title = "QA synthetic product", isProduct = true, amount = "-2.50", tags = "qa", fromReceipt = true))
        exportedCount(1)
        finance.saveTransaction(TransactionDraft(id = id, title = "QA synthetic product", isProduct = true, amount = "-3.75", tags = "qa", fromReceipt = false))
        exportedCount(1)
        val reopened = PersonalHubDatabase.openTemporary(context, PersonalHubDatabase.DATABASE_NAME)
        try {
            val record = reopened.financeDao().transaction(id)!!
            assertEquals("-3.75", record.amount)
            assertFalse(record.fromReceipt)
        } finally { reopened.close() }
        assertEquals(1, scalar("SELECT count(*) FROM hub_sync_pending WHERE table_name='finance_transactions'"))
        finance.deleteTransaction(id)
        exportedCount(0)
        assertEquals(0, scalar("SELECT count(*) FROM finance_transaction_tags"))
        assertEquals(1, scalar("SELECT count(*) FROM hub_sync_pending WHERE table_name='finance_transactions'"))
        // Leave one explicitly synthetic record for UI/read/reopen QA; original Soldi has never been read.
        finance.saveTransaction(TransactionDraft(title = "QA UI transaction", amount = "-10", fromReceipt = true))
        exportedCount(1)
    }
}
