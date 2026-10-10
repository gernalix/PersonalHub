package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.capsules.history.HistoryQueryCapsule
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventDraft
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryQueryCapsuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun filtersReachRareModulesAndOldMatchesBeforePagination() = withDatabase { db ->
        db.beginTransaction()
        try {
            repeat(1100) { event(db, "timer", "busy-$it", 10_000L + it, "Recent session") }
            event(db, "people", "person", 2L, "Old person")
            event(db, "money", "transaction", 3L, "Old transaction")
            event(db, "substances", "intake", 1L, "ketoconazolo")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val people = HistoryQueryCapsule.page(db, HistoryQueryCapsule.Filter(modules = setOf("people")))
        assertEquals(listOf("person"), people.groups.map { it.groupId })
        val money = HistoryQueryCapsule.page(db, HistoryQueryCapsule.Filter(modules = setOf("soldi")))
        assertEquals(listOf("transaction"), money.groups.map { it.groupId })
        val searched = HistoryQueryCapsule.page(db, HistoryQueryCapsule.Filter(query = "ketoconazolo"))
        assertEquals(listOf("intake"), searched.groups.map { it.groupId })
        assertNull(searched.next)
    }

    @Test fun all715GroupsAreReachableWithTiesAndCompleteTransactions() = withDatabase { db ->
        db.beginTransaction()
        try {
            repeat(715) { event(db, "substances", "intake-${it.toString().padStart(4,'0')}", 100L, "Intake") }
            repeat(3) { event(db, "substances", "compound", 101L, "Related $it") }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val found = mutableListOf<String>()
        var cursor: HistoryQueryCapsule.Cursor? = null
        do {
            val page = HistoryQueryCapsule.page(db, HistoryQueryCapsule.Filter(modules = setOf("substances")), cursor)
            assertTrue(page.groups.size <= 50)
            found.addAll(page.groups.map { it.groupId })
            cursor = page.next
        } while (cursor != null)
        assertEquals(716, found.size)
        assertEquals(found.size, found.toSet().size)
        assertEquals(3, MutationEventStore.byTransaction(db, "compound").size)
    }

    @Test fun dedupUsesProvenTransactionAndKeepsUnrelatedLegacyAfterSemanticStart() = withDatabase { db ->
        event(db, "people", "same-action", 1L, "Name")
        for ((id, group) in listOf("equivalent" to "same-action", "distinct" to "another-action")) {
            db.execSQL("INSERT INTO hub_git_history_index(id,occurred_at,author,source,group_id,table_name,operation,row_key,changed_columns,history_path,commit_sha) VALUES(?,2,'user','test',?,'contacts','INSERT','31','name','verified/path','sha')", arrayOf(id,group))
        }
        val page = HistoryQueryCapsule.page(db, HistoryQueryCapsule.Filter(modules = setOf("people")))
        assertEquals(setOf("same-action","another-action"),page.groups.map { it.groupId }.toSet())
        assertEquals(2,page.groups.size)
    }

    @Test fun currentIntakesRemainIndependentOfMutationHistoryAndAll719AreReachable() = withDatabase { db ->
        db.execSQL("INSERT INTO substances(id,name,canonical_name,type,stock_current,stock_unit,dose_per_intake,dose_unit,daily_frequency,start_epoch_day,end_epoch_day,forever,archived,prn,dose_times_csv,days_mask) VALUES(1,'ketoconazolo','ketoconazolo','farmaco',1,'mg',1,'mg',1,1,NULL,1,0,0,'',127)")
        val latest = java.time.Instant.parse("2026-10-07T14:57:28.450Z").toEpochMilli()
        db.beginTransaction()
        try {
            repeat(719) { i -> db.execSQL("INSERT INTO intake_events(id,substance_id,timestamp_ms,timestamp_utc,dose,dose_unit,tap_group_id,quantity,applied_stock_delta,prescription_id) VALUES(?,1,?,?,1,'mg',NULL,1,1,NULL)",arrayOf(i+1,latest-i*1000,latest-i*1000)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val filter=HistoryQueryCapsule.Filter(modules=setOf("substances"),query="ketoconazolo")
        val first=HistoryQueryCapsule.records(db,filter)
        assertEquals(latest,first.groups.first().occurredAt)
        assertEquals("substances/intake",first.groups.first().entityKind)
        assertNotNull(first.groups.first().groupId)
        val found=mutableSetOf<String>()
        var cursor: HistoryQueryCapsule.Cursor?=null
        do {
            val page=HistoryQueryCapsule.records(db,filter,cursor)
            assertTrue(page.groups.size<=50)
            page.groups.forEach { assertTrue(found.add(it.groupId)) }
            cursor=page.next
        } while(cursor!=null)
        assertEquals(719,found.size)
        assertEquals(1,HistoryQueryCapsule.records(db,filter.copy(fromMs=latest,toMs=latest)).groups.size)
    }

    @Test fun externalSearchIndexTracksUpdatesDeletesAndLiteralQueries() = withDatabase { db ->
        event(db,"people","search",1,"Caffè fixture")
        assertEquals(1,HistoryQueryCapsule.page(db,HistoryQueryCapsule.Filter(query="caffe fixture")).groups.size)
        val filter=HistoryQueryCapsule.Filter(query="caffe")
        assertEquals(1,HistoryQueryCapsule.page(db,filter).groups.size)
        db.execSQL("UPDATE mutation_events SET after_json='{\"name\":\"Updated\"}' WHERE transaction_id='search'")
        assertTrue(HistoryQueryCapsule.page(db,filter).groups.isEmpty())
        assertEquals(1,HistoryQueryCapsule.page(db,filter.copy(query="updated")).groups.size)
        db.execSQL("DELETE FROM mutation_events WHERE transaction_id='search'")
        assertTrue(HistoryQueryCapsule.page(db,filter.copy(query="updated")).groups.isEmpty())
        assertTrue(HistoryQueryCapsule.page(db,filter.copy(query="% OR *")).groups.isEmpty())
    }

    @Test fun untitledFinanceRecordsAreVisibleSearchableAndCanonical() = withDatabase { db ->
        db.execSQL("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('account','Fixture','DKK','0',1,1)")
        db.execSQL("INSERT INTO finance_transactions(id,uuid,accountId,amount,currency,fromReceipt,notes,occurredAt,createdAt,updatedAt) VALUES(1,'canonical-transaction','account','3','DKK',0,'Rare payment',1,1,1)")
        val page=HistoryQueryCapsule.records(db,HistoryQueryCapsule.Filter(modules=setOf("soldi"),query="payment"))
        assertEquals(1,page.groups.size)
        assertEquals("canonical-transaction",page.groups.single().groupId)
        assertEquals("Rare payment",page.groups.single().label)
    }

    private fun event(db: SupportSQLiteDatabase, module: String, group: String, time: Long, name: String) {
        MutationEventStore.append(db, MutationEventDraft(transactionId=group,module=module,
            eventType="$module.record.created",entityType="record",entityId=group,
            occurredAt=time,afterJson=org.json.JSONObject().put("name",name).toString()))
    }

    private fun withDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val name = "history-query-${UUID.randomUUID()}.db"
        val database = PersonalHubDatabase.openTemporary(context,name)
        try {
            val db = database.openHelper.writableDatabase
            com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryStore.install(db)
            block(db)
        }
        finally { database.close(); context.deleteDatabase(name) }
    }
}
