package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.DatabaseVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlinx.coroutines.runBlocking
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FinanceCapsuleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun database(block: suspend (PersonalHubDatabase, FinanceCapsule) -> Unit) = runBlocking {
        val name = "finance-${UUID.randomUUID()}.db"
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try { block(owner, FinanceCapsule(owner)) } finally { owner.close(); context.deleteDatabase(name) }
    }
    private fun scalar(db: PersonalHubDatabase, sql: String): Long = db.openHelper.writableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }
    private suspend fun rejects(block: suspend () -> Unit) { try { block(); fail("Expected rejection") } catch (e: Exception) { /* the enclosing assertions verify no partial writes */ } }

    @Test fun productsChainsTagsAndPlacesAreSharedWithAtomicCrudAndJournal() = database { db, finance ->
        db.openHelper.writableDatabase.execSQL("INSERT INTO places(uuid,nickname,created_at,updated_at,archived) VALUES ('qa-place','QA place',1,1,0)")
        val d = TransactionDraft(title = "Milk", isProduct = true, amount = "-12,50", chain = "Shop", placeId = "qa-place", tags = "food, food, weekly")
        val first = finance.saveTransaction(d)
        val second = finance.saveTransaction(d)
        assertEquals(1, scalar(db, "SELECT count(*) FROM finance_products"))
        assertEquals(1, scalar(db, "SELECT count(*) FROM finance_chains"))
        assertEquals(1, scalar(db, "SELECT count(*) FROM places"))
        assertEquals(2, scalar(db, "SELECT count(*) FROM hub_tags WHERE namespace='soldi'"))
        assertEquals(4, scalar(db, "SELECT count(*) FROM hub_tag_assignments WHERE target_binding_id IN (SELECT id FROM hub_entity_bindings WHERE module_id='soldi' AND entity_kind='transaction')"))
        val created = db.financeDao().transaction(first)!!.createdAt
        finance.saveTransaction(d.copy(id = first, amount = "-15", tags = "weekly"))
        assertEquals(created, db.financeDao().transaction(first)!!.createdAt)
        assertEquals("-15", db.financeDao().transaction(first)!!.amount)
        assertTrue(scalar(db, "SELECT count(*) FROM hub_sync_pending WHERE table_name='finance_transactions'") == 2L)
        val generation = scalar(db, "SELECT generation FROM hub_generation")
        rejects { finance.saveTransaction(d.copy(title = "Rollback product", placeId = "missing")) }
        assertEquals(generation, scalar(db, "SELECT generation FROM hub_generation"))
        assertEquals(1, scalar(db, "SELECT count(*) FROM finance_products"))
        rejects { finance.deleteProduct(db.financeDao().productId("Milk")!!) }
        finance.deleteTransaction(first); finance.deleteTransaction(second)
        assertEquals(0, scalar(db, "SELECT count(*) FROM finance_transaction_tags"))
        finance.deleteProduct(db.financeDao().productId("Milk")!!)
        assertEquals(0, scalar(db, "SELECT count(*) FROM finance_products"))
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun receiptOriginIsOnlyABooleanAndMoneyAndTimeRemainExact() = database { db, finance ->
        val id = finance.saveTransaction(TransactionDraft(title = "External purchase", isProduct = true, amount = "-123.45", fromReceipt = true))
        assertTrue(db.financeDao().transaction(id)!!.fromReceipt)
        finance.saveTransaction(TransactionDraft(id = id, title = "External purchase", amount = "-123.45", fromReceipt = false))
        assertFalse(db.financeDao().transaction(id)!!.fromReceipt)
        assertEquals(0, scalar(db, "SELECT count(*) FROM sqlite_master WHERE type='table' AND (name LIKE '%receipt%' OR name LIKE '%soldi%')"))
        val before = scalar(db, "SELECT generation FROM hub_generation")
        rejects { finance.saveTransaction(TransactionDraft(title = "invalid", amount = "NaN")) }
        assertEquals(before, scalar(db, "SELECT generation FROM hub_generation"))
        assertEquals("2026-09-04T10:00:00Z", FinanceCapsule.utc("2026-09-04T12:00:00+02:00"))
        assertEquals("12345678901234567890.01", FinanceCapsule.decimal("12345678901234567890,01"))
    }



    @Test fun semanticIndexAndOwnedItemsRespectAttachmentAndTransactionLifecycle() = database { db, finance ->
        val transactionId = finance.saveTransaction(
            TransactionDraft(title = "Jacket", amount = "-100", currency = "DKK"),
        )
        val attachment = finance.addAttachment(
            transactionId,
            AttachmentDraft(
                kind = "PHOTO_URI",
                uri = "content://qa/jacket",
                title = "front",
                mimeType = "image/jpeg",
            ),
        )
        finance.putPhotoIndex(
            FinancePhotoIndex(
                attachmentId = attachment.id,
                transactionId = transactionId,
                sourceRef = attachment.uri,
                sourceHash = "sha",
                modelId = "tinyclip",
                modelVersion = "1",
                embedding = byteArrayOf(1, 2, 3, 4),
                status = "READY",
            ),
        )
        val owned = finance.trackOwnedItem(transactionId, "Black jacket", attachment.id)
        assertEquals("Black jacket", finance.ownedItem(owned.uuid)?.name)
        assertEquals(attachment.id, finance.ownedItem(owned.uuid)?.primaryAttachmentId)
        assertEquals(1, scalar(db, "SELECT count(*) FROM finance_photo_index"))

        finance.deleteAttachment(attachment.id)
        assertEquals(0, scalar(db, "SELECT count(*) FROM finance_photo_index"))
        assertNull(finance.ownedItem(owned.uuid)?.primaryAttachmentId)

        finance.removeOwnedItem(owned.uuid)
        assertNull(finance.ownedItem(owned.uuid))

        val second = finance.trackOwnedItem(transactionId, "Replacement")
        finance.deleteTransaction(transactionId)
        assertNull(finance.ownedItem(second.uuid))
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

}
