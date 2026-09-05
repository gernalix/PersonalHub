package com.gernalix.personalhub

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gernalix.luoghi.data.PlaceDeleteResult
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.core.database.*
import com.gernalix.personalhub.core.database.capsules.soldi.*
import com.gernalix.personalhub.soldi.R
import com.gernalix.personalhub.soldi.SoldiActivity
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
    private fun guardQaPackage() {
        check(context.packageName == "com.gernalix.personalhub.qa") { "Only the isolated QA package is allowed" }
    }
    private fun scalar(sql: String) = PersonalHubDatabase.get(context).openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun cleanupPlacePolicyRows() {
        PersonalHubDatabase.get(context).openHelper.writableDatabase.apply {
            execSQL("DELETE FROM finance_transaction_tags WHERE transactionId IN (SELECT id FROM finance_transactions WHERE notes='QA place delete policy')")
            execSQL("DELETE FROM finance_transactions WHERE notes='QA place delete policy'")
            execSQL("DELETE FROM finance_stores WHERE placeId LIKE '761284-%'")
            execSQL("DELETE FROM places WHERE uuid LIKE '761284-%'")
        }
    }
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

    private fun UiDevice.text(value: String) =
        wait(Until.findObject(By.text(value)), 5_000) ?: error("Missing UI text: $value")

    private fun UiDevice.typeIntoField(index: Int, value: String) {
        val fields = wait(Until.findObjects(By.clazz("android.widget.EditText")), 5_000)
        require(index in fields.indices) { "Missing text field index $index; found ${fields.size}" }
        fields[index].click()
        waitForIdle()
        fields[index].setText(value)
        waitForIdle()
    }

    @Test fun soldiEditorsFiltersAndNavigationSurviveActivityRecreation() {
        guardQaPackage()
        runBlocking {
            val finance = FinanceCapsule(PersonalHubDatabase.get(context))
            val accountId = "finance-lifecycle-account"
            finance.saveAccount(FinanceAccount(id = accountId, name = "QA lifecycle account", currency = "DKK"))
            finance.saveProduct(null, "QA lifecycle product")
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.pressHome()
        val scenario = ActivityScenario.launch(SoldiActivity::class.java)
        assertTrue(
            "SoldiActivity did not reach the foreground",
            device.wait(Until.hasObject(By.pkg(context.packageName)), 5_000),
        )

        val add = context.getString(R.string.add)
        val back = context.getString(R.string.back)
        val transactions = context.getString(R.string.transactions)
        val products = context.getString(R.string.products)
        val accounts = context.getString(R.string.accounts)
        val reconcile = context.getString(R.string.reconcile)
        val desiredBalance = context.getString(R.string.desired_balance)

        device.text(add).click()
        device.typeIntoField(0, "QA unsaved transaction")
        device.typeIntoField(1, "-12.34")
        device.text("QA unsaved transaction")
        device.text("-12.34")
        scenario.recreate()
        device.text("QA unsaved transaction")
        device.text("-12.34")
        assertEquals(0, scalar("SELECT count(*) FROM finance_transactions WHERE amount='-12.34'"))

        device.text(back).click()
        device.text(products).click()
        device.typeIntoField(0, "lifecycle product")
        scenario.recreate()
        device.text(products)
        device.text("lifecycle product")
        device.text("QA lifecycle product")

        device.text(add).click()
        device.typeIntoField(0, "QA unsaved product")
        scenario.recreate()
        device.text("QA unsaved product")
        device.text(back).click()

        device.text(accounts).click()
        device.text(add).click()
        device.typeIntoField(0, "QA unsaved account")
        scenario.recreate()
        device.text("QA unsaved account")
        device.text(back).click()

        device.text(accounts).click()
        device.text(reconcile).click()
        device.typeIntoField(0, "123")
        scenario.recreate()
        device.text("123")
        device.text(desiredBalance)

        device.text(back).click()
        device.text(transactions).click()
        scenario.recreate()
        device.text(transactions)
        scenario.close()
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

    @Test fun placeDeleteArchivesFinanceReferencesAndPreservesFinanceHistory() = runBlocking {
        guardQaPackage()
        cleanupPlacePolicyRows()
        val db = PersonalHubDatabase.get(context)
        val places = PlaceRepository(context)
        val placeDao = db.placeDao()
        val financeDao = db.financeDao()
        val finance = FinanceCapsule(db)
        try {
            val freePlace = "761284-free-place"
            placeDao.upsertPlace(PlaceEntity(uuid = freePlace, nickname = "QA delete free"))
            assertEquals(PlaceDeleteResult.Deleted, places.deletePlace(freePlace))
            assertNull(placeDao.getPlace(freePlace))

            val transactionPlace = "761284-transaction-place"
            placeDao.upsertPlace(PlaceEntity(uuid = transactionPlace, nickname = "QA delete transaction"))
            val transactionId = finance.saveTransaction(
                TransactionDraft(
                    title = "QA place linked transaction",
                    amount = "-4.20",
                    placeId = transactionPlace,
                    notes = "QA place delete policy",
                )
            )
            PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL(
                "DELETE FROM finance_stores WHERE placeId=?",
                arrayOf(transactionPlace),
            )
            assertEquals(PlaceDeleteResult.ArchivedBecauseReferenced, places.deletePlace(transactionPlace))
            assertTrue(requireNotNull(placeDao.getPlace(transactionPlace)).archived)
            assertEquals(transactionPlace, requireNotNull(financeDao.transaction(transactionId)).placeId)

            val storePlace = "761284-store-place"
            placeDao.upsertPlace(PlaceEntity(uuid = storePlace, nickname = "QA delete store"))
            financeDao.add(FinanceStore(storePlace, chainId = null))
            assertEquals(PlaceDeleteResult.ArchivedBecauseReferenced, places.deletePlace(storePlace))
            assertTrue(requireNotNull(placeDao.getPlace(storePlace)).archived)
            assertNotNull(financeDao.store(storePlace))

            assertEquals(PlaceDeleteResult.NotFound, places.deletePlace("761284-missing-place"))
        } finally {
            cleanupPlacePolicyRows()
        }
    }
}
