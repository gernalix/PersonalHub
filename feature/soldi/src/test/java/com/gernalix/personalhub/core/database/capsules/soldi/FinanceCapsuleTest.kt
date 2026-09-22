package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.contracts.database.HubEntityRef
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
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
    private fun text(db: PersonalHubDatabase, sql: String): String = db.openHelper.writableDatabase.query(sql).use { it.moveToFirst(); it.getString(0) }
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
        assertEquals(4, scalar(db, "SELECT count(*) FROM hub_tag_assignments WHERE tag_id IN (SELECT id FROM hub_tags WHERE namespace='soldi')"))
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
        assertEquals(0, scalar(db, "SELECT count(*) FROM hub_tag_assignments WHERE tag_id IN (SELECT id FROM hub_tags WHERE namespace='soldi')"))
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

    @Test fun primaryCategoryAndFreeTagsUseSharedStorageAndAggregateExactSpendingByPeriod() = database { db, finance ->
        val first = finance.saveTransaction(TransactionDraft(
            title = "Groceries", amount = "-12.50", currency = "EUR",
            occurredAt = "2026-09-01T10:00:00Z", tags = "food, weekly", category = "Living",
        ))
        finance.saveTransaction(TransactionDraft(
            title = "More groceries", amount = "-7.50", currency = "EUR",
            occurredAt = "2026-09-02T10:00:00Z", tags = "food", category = "Living",
        ))
        finance.saveTransaction(TransactionDraft(
            title = "Outside period", amount = "-100", currency = "EUR",
            occurredAt = "2026-10-01T10:00:00Z", tags = "food", category = "Living",
        ))

        assertEquals(0, scalar(db, "SELECT count(*) FROM pragma_table_info('finance_transactions') WHERE name='category'"))
        assertEquals("Living", text(db, "SELECT ht.name FROM hub_tags ht JOIN hub_tag_assignments a ON a.tag_id=ht.id JOIN hub_entity_bindings b ON b.id=a.target_binding_id JOIN finance_transactions tx ON tx.uuid=b.canonical_id WHERE tx.id=$first AND ht.namespace='soldi.category' AND ht.kind='CATEGORY'"))
        assertEquals(listOf("food", "weekly"), finance.tags(first).sorted())

        finance.saveTransaction(TransactionDraft(
            id = first, title = "Groceries", amount = "-12.50", currency = "EUR",
            occurredAt = "2026-09-01T10:00:00Z", tags = "food, weekly", category = "Essentials",
        ))
        assertEquals(1, scalar(db, "SELECT count(*) FROM hub_tag_assignments a JOIN hub_tags ht ON ht.id=a.tag_id JOIN hub_entity_bindings b ON b.id=a.target_binding_id JOIN finance_transactions tx ON tx.uuid=b.canonical_id WHERE tx.id=$first AND ht.namespace='soldi.category'"))

        val totals = finance.spendingByTag(
            FinanceCapsule.epoch("2026-09-01T00:00:00Z"),
            FinanceCapsule.epoch("2026-10-01T00:00:00Z"),
        ).associateBy { it.namespace to it.tagName }
        assertEquals("20", totals["soldi" to "food"]?.amount)
        assertEquals("12.5", totals["soldi" to "weekly"]?.amount)
        assertEquals("12.5", totals["soldi.category" to "Essentials"]?.amount)
        assertEquals("7.5", totals["soldi.category" to "Living"]?.amount)
    }

    @Test fun splittingRecurrenceCopiesCommonFreeAndCategoryTags() = database { _, finance ->
        val account = FinanceAccount(name = "Recurring", currency = "EUR")
        finance.saveAccount(account)
        val sourceId = finance.saveRecurrence(RecurrenceDraft(
            title = "Rent", amount = "100", currency = "EUR", accountId = account.id,
            dayOfMonth = 1, startDate = "2026-09-01", tags = "home, fixed", category = "Housing",
        ))

        val followingId = finance.editRecurrenceAmount(
            sourceId, LocalDate.parse("2026-10-01"), "110",
            scope = RecurrenceEditScope.THIS_AND_FOLLOWING,
        )

        assertNotEquals(sourceId, followingId)
        assertEquals(listOf("fixed", "home"), finance.recurrenceTags(followingId).sorted())
        assertEquals("Housing", finance.category(HubEntityRef("soldi", "recurrence", followingId)))
    }

    @Test fun versionThreeUpgradeKeepsExistingRowsAndCreatesOnlyEmptyFinanceTables() {
        val name = "finance-upgrade-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i); val table = e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO hub_generation VALUES(1,42)")
            old.execSQL("INSERT INTO sessions VALUES (1,'keep exactly',1000,2000,NULL,1000,2000,NULL)")
            old.execSQL("INSERT INTO hub_preferences VALUES ('finance-test','{\"preserve\":true}')")
            old.execSQL(com.gernalix.personalhub.core.database.capsules.sync.SyncJournal.trigger("sessions", listOf("id"), "INSERT", legacy = true))
            old.version = 3
        }
        assertEquals(42, DatabaseVault.validate(context, file))
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            assertEquals(PersonalHubDatabase.SCHEMA_VERSION, owner.openHelper.writableDatabase.version)
            assertEquals(56, scalar(owner, "SELECT generation FROM hub_generation"))
            owner.openHelper.writableDatabase.query("SELECT title,start_ms,end_ms FROM sessions").use { assertTrue(it.moveToFirst()); assertEquals("keep exactly", it.getString(0)); assertEquals(1000, it.getInt(1)); assertEquals(2000, it.getInt(2)) }
            owner.openHelper.writableDatabase.query("SELECT json FROM hub_preferences").use { assertTrue(it.moveToFirst()); assertEquals("{\"preserve\":true}", it.getString(0)) }
            val names = owner.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'finance_%'").use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
            assertEquals(14, names.size)
            names.forEach { assertEquals(0, scalar(owner, "SELECT count(*) FROM $it")) }
            owner.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            owner.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            assertEquals(56, DatabaseVault.validate(context, file))
        } finally { owner.close(); context.deleteDatabase(name) }
    }
}
