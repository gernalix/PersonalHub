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
    @Test fun deterministicExchangeAtomicConflictsAndCredentialFreeConfiguration() = db { db,finance ->
        val account=FinanceAccount(name="QA",currency="DKK"); finance.saveAccount(account)
        finance.saveTransaction(TransactionDraft(title="QA transaction",amount="-2",accountId=account.id))
        val exchange=FinanceExchange(db); val before=exchange.export(); assertEquals(before,exchange.export())
        val baseline=exchange.import(before,emptyMap()); assertEquals(before,exchange.export())
        exchange.import(before,baseline); assertEquals(before,exchange.export())
        val other=PersonalHubDatabase.openTemporary(context,"exchange-other.db")
        try {
            val copy=FinanceExchange(other); copy.import(before,emptyMap()); assertEquals(before,copy.export())
        } finally { other.close();context.deleteDatabase("exchange-other.db") }
        val changed=JSONObject(before); val tx=changed.getJSONArray("transactions").getJSONObject(0)
        tx.put("amount","-3");tx.put("updatedAt",Instant.now().plusSeconds(1).toString())
        val accepted=exchange.import(changed.toString(),baseline)
        val row=db.financeDao().allTransactions().single()
        finance.saveTransaction(TransactionDraft(id=row.id,title="local edit",amount="-4",accountId=account.id))
        val local=exchange.export();tx.put("amount","-5");tx.put("updatedAt",Instant.now().plusSeconds(2).toString())
        rejects { exchange.import(changed.toString(),accepted) }; assertEquals(local,exchange.export())
        val malformed=JSONObject(local);malformed.getJSONArray("accounts").getJSONObject(0).put("name","must rollback")
        malformed.getJSONArray("transactions").getJSONObject(0).put("accountId",UUID.randomUUID().toString())
        rejects { exchange.import(malformed.toString(),FinanceExchange.fingerprints(local)) }; assertEquals(local,exchange.export())
        listOf("https://user:secret@example.com/repo","https://example.com/repo?token=secret","file:///tmp/repo","http://example.com/repo").forEach { bad -> rejects { FinanceGit.validateUrl(bad) } }
        FinanceGit.validateUrl("https://example.com/repo.git")
        assertFalse(local.contains("token"));assertFalse(local.contains("soldi_git"))
    }
    @Test fun migrationPreservesAllFinanceValuesAndTagLinksWithRequiredAccounts() = runBlocking {
        val name="finance-v4.db";val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        val schema=JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            for(i in 0 until schema.length()) {
                val e=schema.getJSONObject(i);val table=e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=e.optJSONArray("indices") ?: continue
                for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            old.execSQL("INSERT INTO hub_generation VALUES(1,9)")
            old.execSQL("INSERT INTO finance_products VALUES(7,'Milk')")
            old.execSQL("INSERT INTO finance_tags VALUES(3,'food')")
            old.execSQL("INSERT INTO finance_transactions VALUES(5,NULL,7,'-3.5','DKK',NULL,NULL,1,'keep','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")
            old.execSQL("INSERT INTO finance_transaction_tags VALUES(5,3)")
            old.version=4
        }
        val db=PersonalHubDatabase.openTemporary(context,name)
        try {
            val row=db.financeDao().transaction(5)!!;assertEquals("-3.5",row.amount);assertEquals(7L,row.productId);assertTrue(row.fromReceipt);assertEquals("keep",row.notes)
            assertEquals("DKK",db.financeDao().account(row.accountId)!!.currency)
            assertEquals(listOf("food"),db.financeDao().tags(5))
            assertEquals(1,db.financeDao().allTransactions().size)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close();context.deleteDatabase(name) }
    }
}
