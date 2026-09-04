package com.gernalix.personalhub

import android.content.Context
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
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class FinanceAccountsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun scalar(sql:String)=PersonalHubDatabase.get(context).openHelper.readableDatabase.query(sql).use { it.moveToFirst();it.getLong(0) }
    @Test fun accountsProductsReconciliationAndGitUseNormalPersistence() = runBlocking {
        check(context.packageName == "com.gernalix.personalhub.qa")
        check(DatabaseVault.folder(context)?.contains("PersonalHubSoldiQA") == true)
        val db=PersonalHubDatabase.get(context);val finance=FinanceCapsule(db)
        assertEquals(0,scalar("SELECT count(*) FROM finance_accounts"))
        val a=FinanceAccount(name="QA Lunar",currency="DKK",openingBalance="100")
        val b=FinanceAccount(name="QA Revolut",currency="DKK",openingBalance="50")
        finance.saveAccount(a);finance.saveAccount(b)
        val product=finance.saveProduct(null,"QA catalog product")
        val transaction=finance.saveTransaction(TransactionDraft(title="QA catalog product",isProduct=true,productId=product,accountId=a.id,amount="-10"))
        finance.saveTransaction(TransactionDraft(title="QA income",accountId=b.id,amount="5"))
        assertEquals("145",FinanceCapsule.totals(db.financeDao().allAccounts(),db.financeDao().allTransactions())["DKK"]!!.toPlainString())
        finance.setIncluded(b.id,false)
        assertEquals("90",FinanceCapsule.totals(db.financeDao().allAccounts(),db.financeDao().allTransactions())["DKK"]!!.toPlainString())
        val at=Instant.now().toString()
        val compensation=finance.reconcile(a.id,at,"120","QA reconciliation","")!!
        assertEquals("30",db.financeDao().transaction(compensation)!!.amount)
        assertNull(finance.reconcile(a.id,at,"120","QA reconciliation",""))
        finance.saveProduct(product,"QA renamed product")
        assertEquals(product,db.financeDao().transaction(transaction)!!.productId)
        val exchange=FinanceExchange(db); val text=exchange.export()
        val git=FinanceGit(context);git.configure("http://127.0.0.1:8765/finance.git")
        git.push();git.pull();git.pull()
        assertEquals(text,exchange.export());assertEquals(3,scalar("SELECT count(*) FROM finance_transactions"))
        assertEquals(3,scalar("SELECT count(*) FROM hub_sync_pending WHERE table_name='finance_transactions'"))
        val reopened=PersonalHubDatabase.openTemporary(context,PersonalHubDatabase.DATABASE_NAME)
        try { assertEquals(product,reopened.financeDao().transaction(transaction)!!.productId); assertFalse(reopened.financeDao().account(b.id)!!.included) } finally { reopened.close() }
        val generation=scalar("SELECT generation FROM hub_generation")
        val deadline=System.currentTimeMillis()+45000
        var verified=false
        while(System.currentTimeMillis()<deadline && !verified) {
            val root=DocumentFile.fromTreeUri(context,Uri.parse(DatabaseVault.folder(context)))!!
            val file=root.findFile("personalhub.db")
            if(file!=null) {
                val copy=File(context.cacheDir,"accounts-readback.db")
                try { context.contentResolver.openInputStream(file.uri)!!.use { i -> copy.outputStream().use { i.copyTo(it) } }; verified=DatabaseVault.validate(context,copy)==generation }
                catch(_: Exception) {} finally { copy.delete() }
            }
            if(!verified)Thread.sleep(300)
        }
        assertTrue("Automatic SAF export",verified)
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}
