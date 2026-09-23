package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FinanceAccountsTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    private fun db(block: suspend (PersonalHubDatabase,FinanceCapsule) -> Unit) = runBlocking {
        val name = "accounts-${UUID.randomUUID()}.db"; val db = PersonalHubDatabase.openTemporary(context,name)
        try { block(db,FinanceCapsule(db)) } finally { db.close(); context.deleteDatabase(name) }
    }
    private suspend fun rejects(block: suspend () -> Unit) { try { block(); fail("Expected rejection") } catch(_: Exception) {} }
    @Test fun balancesSubsetProductIdentityAndReconciliation() = db { db,finance ->
        val a = FinanceAccount(name="Lunar",currency="DKK",openingBalance="100")
        val b = FinanceAccount(name="Revolut",currency="DKK",openingBalance="50")
        finance.saveAccount(a); finance.saveAccount(b)
        finance.saveAccount(FinanceAccount(name="Euro",currency="EUR",openingBalance="20"))
        val product = finance.saveProduct(null,"Milk")
        val draft = TransactionDraft(title="ignored copy",isProduct=true,productId=product,accountId=a.id,amount="-10",occurredAt="2026-01-01T12:00:00Z")
        val id=finance.saveTransaction(draft)
        finance.saveProduct(product,"Renamed milk")
        finance.saveTransaction(draft.copy(id=id,amount="-12"))
        assertEquals(product,db.financeDao().transaction(id)!!.productId)
        assertEquals("Renamed milk",finance.transactions.first().first().title)
        assertEquals("138",FinanceCapsule.totals(db.financeDao().allAccounts(),db.financeDao().allTransactions(),Instant.parse("2026-01-02T00:00:00Z"))["DKK"]!!.toPlainString())
        assertEquals("20",FinanceCapsule.totals(db.financeDao().allAccounts(),db.financeDao().allTransactions())["EUR"]!!.toPlainString())
        assertEquals(2,FinanceCapsule.totals(db.financeDao().allAccounts(),db.financeDao().allTransactions()).size)
        finance.setIncluded(b.id,false)
        assertEquals("88",FinanceCapsule.totals(db.financeDao().allAccounts(),db.financeDao().allTransactions(),Instant.parse("2026-01-02T00:00:00Z"))["DKK"]!!.toPlainString())
        val compensation=finance.reconcile(a.id,"2026-01-02T00:00:00Z","120","Reconciliation","")!!
        assertEquals("32",db.financeDao().transaction(compensation)!!.amount)
        assertNull(finance.reconcile(a.id,"2026-01-02T00:00:00Z","120","Reconciliation",""))
        assertEquals("120",FinanceCapsule.balance(a,db.financeDao().allTransactions(),Instant.parse("2026-01-02T00:00:00Z")).toPlainString())
        assertEquals("100",FinanceCapsule.balance(a,db.financeDao().allTransactions(),Instant.parse("2025-12-31T00:00:00Z")).toPlainString())
        rejects { finance.saveTransaction(draft.copy(accountId=UUID.randomUUID().toString())) }
        rejects { finance.saveTransaction(draft.copy(currency="EUR")) }
        finance.deleteTransaction(compensation)
        assertEquals("88",FinanceCapsule.balance(a,db.financeDao().allTransactions()).toPlainString())
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    @Test fun titleSuggestionCopiesLatestFieldsButPreservesDateAndIdentity() = db { db, finance ->
        val a = FinanceAccount(name="Cash", currency="DKK")
        val b = FinanceAccount(name="Euro", currency="EUR")
        finance.saveAccount(a); finance.saveAccount(b)
        val newest = TransactionDraft(title="Groceries", amount="-24.5", currency="EUR", accountId=b.id,
            chain="Market", notes="Latest details", tags="food, weekly", fromReceipt=true,
            occurredAt="2026-06-02T12:00:00Z")
        finance.saveTransaction(newest)
        // An older occurrence inserted later must not become the template.
        finance.saveTransaction(TransactionDraft(title="Groceries", amount="-1", accountId=a.id, occurredAt="2026-06-01T12:00:00Z"))
        val draft = TransactionDraft(id=999, title="Gro", occurredAt="2026-09-04T12:34:56Z")
        val filled = finance.reuseLatestTitle(draft, "Groceries")
        assertEquals(newest.copy(id=draft.id, occurredAt=draft.occurredAt), filled)
        assertEquals(2, db.financeDao().allTransactions().size)
        val id = finance.saveTransaction(filled.copy(id=null))
        assertEquals(Instant.parse(draft.occurredAt).toEpochMilli(), db.financeDao().transaction(id)!!.occurredAt)
        assertEquals(b.id, db.financeDao().transaction(id)!!.accountId)
        assertEquals(listOf("food", "weekly"), finance.tags(id))
    }
    @Test fun sameDayRecurrenceMaterializesAtAccountOpeningInstant() = db { db, finance ->
        val date = LocalDate.of(2026, 9, 13)
        val openedAt = date.atTime(14, 30).atZone(ZoneId.systemDefault()).toInstant().toString()
        val account = FinanceAccount(name="Same-day EUR", currency="EUR", openedAt=Instant.parse(openedAt).toEpochMilli())
        finance.saveAccount(account)
        val recurrenceId = finance.saveRecurrence(
            RecurrenceDraft(
                title="Same-day recurring expense",
                amount="45.67",
                currency="EUR",
                accountId=account.id,
                dayOfMonth=date.dayOfMonth,
                startDate=date.toString(),
            ),
        )

        val ids = finance.materializeDueRecurrences(date)

        assertEquals(1, ids.size)
        val row = requireNotNull(db.financeDao().transaction(ids.single()))
        assertEquals(account.openedAt, row.occurredAt)
        assertEquals(recurrenceId, row.recurrenceId)
        assertEquals(date.toString(), row.occurrenceKey)
        assertTrue(row.occurredAt >= account.openedAt)
    }

}
