package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.soldi.AttachmentDraft
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceReceiptImport
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceReceiptImportItem
import com.gernalix.personalhub.core.database.capsules.soldi.RecurrenceDraft
import com.gernalix.personalhub.core.database.capsules.soldi.RecurrenceEditScope
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionDraft
import com.gernalix.personalhub.core.database.capsules.soldi.TransferDraft
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancePersistenceDeviceTest {
    @TableProbe("finance_transfers", "finance_macros", "finance_recurrences", "finance_recurrence_tags", "finance_recurrence_overrides")
    @Test fun transferReceiptAndRecurrencePersistThroughCapsule() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val finance = FinanceCapsule(owner)
        val dao = owner.financeDao()
        val marker = "QA914263Finance${UUID.randomUUID()}"
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        var transferSourceTransaction = 0L
        var macroId: String? = null
        var recurrenceId: String? = null
        var productId: Long? = null
        val day = LocalDate.now().plusDays(2)
        try {
            finance.saveAccount(FinanceAccount(id = source, name = marker, currency = "DKK"))
            finance.saveAccount(FinanceAccount(id = target, name = "${marker}Target", currency = "DKK"))
            val transfer = finance.saveTransfer(TransferDraft(
                title = marker, sourceAccountId = source, targetAccountId = target,
                sourceAmount = "10", targetAmount = "10",
            ))
            transferSourceTransaction = transfer.sourceTransactionId
            assertEquals(transfer.id, dao.transferForTransaction(transferSourceTransaction)?.id)
            finance.saveTransfer(TransferDraft(
                transferId = transfer.id, title = marker, sourceAccountId = source,
                targetAccountId = target, sourceAmount = "12", targetAmount = "12",
            ))
            assertEquals("-12", dao.transaction(transferSourceTransaction)?.amount)

            val receipt = finance.importReceipt(FinanceReceiptImport(
                merchant = marker, occurredAt = java.time.Instant.now().toString(),
                currency = "DKK", accountId = source,
                items = listOf(FinanceReceiptImportItem(marker, marker, null, "3")),
            ))
            val receiptTransaction = requireNotNull(dao.transaction(receipt.single().transactionId))
            macroId = receiptTransaction.macroId
            productId = receiptTransaction.productId
            assertNotNull(macroId?.let { dao.macro(it) })

            recurrenceId = finance.saveRecurrence(RecurrenceDraft(
                title = marker, amount = "7", currency = "DKK", accountId = source,
                dayOfMonth = day.dayOfMonth, startDate = day.toString(), tags = marker,
            ))
            assertEquals(marker, dao.recurrence(recurrenceId)?.title)
            assertEquals(listOf(marker), finance.recurrenceTags(recurrenceId))
            finance.setRecurrenceEnabled(recurrenceId, false)
            assertEquals(false, dao.recurrence(recurrenceId)?.enabled)
            finance.editRecurrenceAmount(recurrenceId, day, "8", scope = RecurrenceEditScope.ONLY_THIS)
            assertEquals("-8", dao.recurrenceOverride(recurrenceId, day.toString())?.amount)
        } finally {
            recurrenceId?.let { finance.deleteRecurrence(it) }
            macroId?.let { finance.deleteMacro(it) }
            if (transferSourceTransaction != 0L) finance.deleteTransaction(transferSourceTransaction)
            productId?.let { finance.deleteProduct(it) }
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_accounts WHERE id IN (?,?)", arrayOf(source, target))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_tags WHERE name=?", arrayOf(marker))
            owner.openHelper.readableDatabase.query("SELECT count(*) FROM finance_accounts WHERE id IN (?,?)", arrayOf(source, target)).use {
                it.moveToFirst(); assertEquals(0L, it.getLong(0))
            }
        }
    }

    @TableProbe("finance_accounts", "finance_products", "finance_titles", "finance_chains", "finance_transactions", "finance_tags", "finance_transaction_tags", "finance_attachments")
    @Test fun transactionGraphPersistsAndEditsThroughCapsule() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val finance = FinanceCapsule(owner)
        val dao = owner.financeDao()
        val marker = "QA914263Finance${UUID.randomUUID()}"
        val accountId = UUID.randomUUID().toString()
        var productTransaction = 0L
        var titledTransaction = 0L
        var attachmentId: String? = null
        fun count(table: String, where: String, arg: Any): Long =
            owner.openHelper.readableDatabase.query("SELECT count(*) FROM $table WHERE $where=?", arrayOf(arg)).use {
                it.moveToFirst(); it.getLong(0)
            }
        try {
            finance.saveAccount(FinanceAccount(id = accountId, name = marker, currency = "DKK"))
            assertEquals(marker, dao.account(accountId)?.name)
            finance.saveAccount(requireNotNull(dao.account(accountId)).copy(name = "${marker}Edited"))
            assertEquals("${marker}Edited", dao.account(accountId)?.name)

            productTransaction = finance.saveTransaction(TransactionDraft(
                title = marker, isProduct = true, amount = "-10", accountId = accountId,
                chain = marker, tags = marker,
            ))
            assertEquals(1L, count("finance_products", "name", marker))
            assertEquals(1L, count("finance_chains", "name", marker))
            assertEquals(1L, count("finance_tags", "name", marker))
            assertEquals(1L, count("finance_transaction_tags", "transactionId", productTransaction))
            assertEquals("-10", dao.transaction(productTransaction)?.amount)

            titledTransaction = finance.saveTransaction(TransactionDraft(
                title = "${marker}Title", amount = "-5", accountId = accountId,
            ))
            assertEquals(1L, count("finance_titles", "name", "${marker}Title"))
            finance.saveTransaction(TransactionDraft(
                id = productTransaction, title = marker, isProduct = true,
                amount = "-12", accountId = accountId, chain = marker, tags = "${marker}Edited",
            ))
            assertEquals("-12", dao.transaction(productTransaction)?.amount)
            assertEquals(listOf("${marker}Edited"), finance.tags(productTransaction))
            val attachment = finance.addAttachment(productTransaction, AttachmentDraft("image", "content://qa914263/$marker", marker))
            attachmentId = attachment.id
            assertEquals(1L, count("finance_attachments", "id", attachment.id))
            assertNotNull(dao.transaction(titledTransaction))
        } finally {
            attachmentId?.let { finance.deleteAttachment(it) }
            if (productTransaction != 0L) finance.deleteTransaction(productTransaction)
            if (titledTransaction != 0L) finance.deleteTransaction(titledTransaction)
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_accounts WHERE id=?", arrayOf(accountId))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_products WHERE name=?", arrayOf(marker))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_titles WHERE name=?", arrayOf("${marker}Title"))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_chains WHERE name=?", arrayOf(marker))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_tags WHERE name IN (?,?)", arrayOf(marker, "${marker}Edited"))
            assertEquals(0L, count("finance_accounts", "id", accountId))
            assertEquals(0L, count("finance_transactions", "accountId", accountId))
        }
    }
}
