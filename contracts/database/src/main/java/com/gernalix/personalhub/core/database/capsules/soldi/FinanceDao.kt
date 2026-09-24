package com.gernalix.personalhub.core.database.capsules.soldi

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceDao {
    @Query("""
        SELECT t.*, COALESCE(p.name,n.name,'') AS title, c.name AS chain,
          COALESCE(l.nickname,(SELECT pl.nickname FROM hub_contexts hc JOIN hub_context_members am ON am.context_id=hc.id JOIN hub_entity_bindings ab ON ab.id=am.entity_id JOIN hub_context_members cm ON cm.context_id=hc.id JOIN hub_entity_bindings cb ON cb.id=cm.entity_id JOIN places pl ON pl.uuid=cb.canonical_id WHERE hc.context_type_id='finance_transaction_context' AND ab.module_id='soldi' AND ab.entity_kind='transaction' AND ab.canonical_id=t.uuid AND cb.module_id='places' AND cb.entity_kind='place' LIMIT 1)) AS place,
          COALESCE(pf.value,(SELECT cf.value FROM hub_contexts hc JOIN hub_context_members am ON am.context_id=hc.id JOIN hub_entity_bindings ab ON ab.id=am.entity_id JOIN hub_context_members cm ON cm.context_id=hc.id JOIN hub_entity_bindings cb ON cb.id=cm.entity_id JOIN contacts pc ON pc.public_id=cb.canonical_id JOIN contact_fields cf ON cf.id=(SELECT id FROM contact_fields WHERE contact_id=pc.id AND field_type='name' ORDER BY is_primary DESC,position,id LIMIT 1) WHERE hc.context_type_id='finance_transaction_context' AND ab.module_id='soldi' AND ab.entity_kind='transaction' AND ab.canonical_id=t.uuid AND cb.module_id='people' AND cb.entity_kind='person' LIMIT 1)) AS person,
          COALESCE((SELECT ht.name FROM hub_entity_bindings hb JOIN hub_tag_assignments ha ON ha.target_binding_id=hb.id JOIN hub_tags ht ON ht.id=ha.tag_id WHERE hb.module_id='soldi' AND hb.entity_kind='transaction' AND hb.canonical_id=t.uuid AND ht.namespace='soldi.category' LIMIT 1),'') AS category
        FROM finance_transactions t
        LEFT JOIN finance_products p ON p.id=t.productId
        LEFT JOIN finance_titles n ON n.id=t.titleId
        LEFT JOIN finance_chains c ON c.id=t.chainId
        LEFT JOIN places l ON l.uuid=t.placeId
        LEFT JOIN contact_fields pf ON pf.id=(SELECT id FROM contact_fields WHERE contact_id=t.personId AND field_type='name' ORDER BY is_primary DESC, position ASC, id ASC LIMIT 1)
        ORDER BY t.occurredAt DESC,t.id DESC
    """)
    fun transactions(): Flow<List<TransactionView>>

    @Query("SELECT t.* FROM finance_transactions t JOIN finance_titles n ON n.id=t.titleId WHERE n.name=:title")
    suspend fun transactionsWithTitle(title: String): List<FinanceTransaction>

    @Query("SELECT * FROM finance_accounts ORDER BY name,id") fun accounts(): Flow<List<FinanceAccount>>
    @Query("SELECT * FROM finance_accounts ORDER BY id") suspend fun allAccounts(): List<FinanceAccount>
    @Query("SELECT * FROM finance_accounts WHERE id=:id") suspend fun account(id: String): FinanceAccount?
    @Query("SELECT * FROM finance_products WHERE id=:id") suspend fun product(id: Long): FinanceProduct?
    @Query("SELECT * FROM finance_products WHERE uuid=:uuid") suspend fun productByUuid(uuid: String): FinanceProduct?
    @Query("SELECT * FROM finance_products ORDER BY uuid") suspend fun allProducts(): List<FinanceProduct>
    @Query("SELECT * FROM finance_transactions ORDER BY uuid") suspend fun allTransactions(): List<FinanceTransaction>
    @Query("SELECT * FROM finance_transactions WHERE uuid=:uuid") suspend fun transactionByUuid(uuid: String): FinanceTransaction?

    @Query("""
        SELECT t.*, COALESCE(p.name,n.name,'') AS title, c.name AS chain,
          COALESCE(l.nickname,(SELECT pl.nickname FROM hub_contexts hc JOIN hub_context_members am ON am.context_id=hc.id JOIN hub_entity_bindings ab ON ab.id=am.entity_id JOIN hub_context_members cm ON cm.context_id=hc.id JOIN hub_entity_bindings cb ON cb.id=cm.entity_id JOIN places pl ON pl.uuid=cb.canonical_id WHERE hc.context_type_id='finance_transaction_context' AND ab.module_id='soldi' AND ab.entity_kind='transaction' AND ab.canonical_id=t.uuid AND cb.module_id='places' AND cb.entity_kind='place' LIMIT 1)) AS place,
          COALESCE(pf.value,(SELECT cf.value FROM hub_contexts hc JOIN hub_context_members am ON am.context_id=hc.id JOIN hub_entity_bindings ab ON ab.id=am.entity_id JOIN hub_context_members cm ON cm.context_id=hc.id JOIN hub_entity_bindings cb ON cb.id=cm.entity_id JOIN contacts pc ON pc.public_id=cb.canonical_id JOIN contact_fields cf ON cf.id=(SELECT id FROM contact_fields WHERE contact_id=pc.id AND field_type='name' ORDER BY is_primary DESC,position,id LIMIT 1) WHERE hc.context_type_id='finance_transaction_context' AND ab.module_id='soldi' AND ab.entity_kind='transaction' AND ab.canonical_id=t.uuid AND cb.module_id='people' AND cb.entity_kind='person' LIMIT 1)) AS person,
          COALESCE((SELECT ht.name FROM hub_entity_bindings hb JOIN hub_tag_assignments ha ON ha.target_binding_id=hb.id JOIN hub_tags ht ON ht.id=ha.tag_id WHERE hb.module_id='soldi' AND hb.entity_kind='transaction' AND hb.canonical_id=t.uuid AND ht.namespace='soldi.category' LIMIT 1),'') AS category
        FROM finance_transactions t
        LEFT JOIN finance_products p ON p.id=t.productId
        LEFT JOIN finance_titles n ON n.id=t.titleId
        LEFT JOIN finance_chains c ON c.id=t.chainId
        LEFT JOIN places l ON l.uuid=t.placeId
        LEFT JOIN contact_fields pf ON pf.id=(SELECT id FROM contact_fields WHERE contact_id=t.personId AND field_type='name' ORDER BY is_primary DESC, position ASC, id ASC LIMIT 1)
        WHERE t.uuid IN (:uuids)
        ORDER BY t.occurredAt DESC,t.id DESC
    """)
    suspend fun transactionViewsByUuid(uuids: List<String>): List<TransactionView>

    @Query("""
        SELECT t.*, COALESCE(p.name,n.name,'') AS title, c.name AS chain,
          COALESCE(l.nickname,(SELECT pl.nickname FROM hub_contexts hc JOIN hub_context_members am ON am.context_id=hc.id JOIN hub_entity_bindings ab ON ab.id=am.entity_id JOIN hub_context_members cm ON cm.context_id=hc.id JOIN hub_entity_bindings cb ON cb.id=cm.entity_id JOIN places pl ON pl.uuid=cb.canonical_id WHERE hc.context_type_id='finance_transaction_context' AND ab.module_id='soldi' AND ab.entity_kind='transaction' AND ab.canonical_id=t.uuid AND cb.module_id='places' AND cb.entity_kind='place' LIMIT 1)) AS place,
          COALESCE(pf.value,(SELECT cf.value FROM hub_contexts hc JOIN hub_context_members am ON am.context_id=hc.id JOIN hub_entity_bindings ab ON ab.id=am.entity_id JOIN hub_context_members cm ON cm.context_id=hc.id JOIN hub_entity_bindings cb ON cb.id=cm.entity_id JOIN contacts pc ON pc.public_id=cb.canonical_id JOIN contact_fields cf ON cf.id=(SELECT id FROM contact_fields WHERE contact_id=pc.id AND field_type='name' ORDER BY is_primary DESC,position,id LIMIT 1) WHERE hc.context_type_id='finance_transaction_context' AND ab.module_id='soldi' AND ab.entity_kind='transaction' AND ab.canonical_id=t.uuid AND cb.module_id='people' AND cb.entity_kind='person' LIMIT 1)) AS person,
          COALESCE((SELECT ht.name FROM hub_entity_bindings hb JOIN hub_tag_assignments ha ON ha.target_binding_id=hb.id JOIN hub_tags ht ON ht.id=ha.tag_id WHERE hb.module_id='soldi' AND hb.entity_kind='transaction' AND hb.canonical_id=t.uuid AND ht.namespace='soldi.category' LIMIT 1),'') AS category
        FROM finance_transactions t
        LEFT JOIN finance_products p ON p.id=t.productId
        LEFT JOIN finance_titles n ON n.id=t.titleId
        LEFT JOIN finance_chains c ON c.id=t.chainId
        LEFT JOIN places l ON l.uuid=t.placeId
        LEFT JOIN contact_fields pf ON pf.id=(SELECT id FROM contact_fields WHERE contact_id=t.personId AND field_type='name' ORDER BY is_primary DESC, position ASC, id ASC LIMIT 1)
        WHERE COALESCE(p.name,n.name,'') LIKE '%' || :query || '%'
           OR t.notes LIKE '%' || :query || '%'
           OR EXISTS (SELECT 1 FROM hub_entity_bindings hb JOIN hub_tag_assignments ha ON ha.target_binding_id=hb.id JOIN hub_tags ht ON ht.id=ha.tag_id WHERE hb.module_id='soldi' AND hb.entity_kind='transaction' AND hb.canonical_id=t.uuid AND ht.name LIKE '%' || :query || '%')
           OR COALESCE(pf.value,'') LIKE '%' || :query || '%'
        ORDER BY t.occurredAt DESC,t.id DESC LIMIT :limit
    """)
    suspend fun searchTransactionViews(query: String, limit: Int): List<TransactionView>

    @Query("""
        SELECT t.*, COALESCE(p.name,n.name,'') AS title, c.name AS chain,
          COALESCE(l.nickname,(SELECT pl.nickname FROM hub_contexts hc JOIN hub_context_members am ON am.context_id=hc.id JOIN hub_entity_bindings ab ON ab.id=am.entity_id JOIN hub_context_members cm ON cm.context_id=hc.id JOIN hub_entity_bindings cb ON cb.id=cm.entity_id JOIN places pl ON pl.uuid=cb.canonical_id WHERE hc.context_type_id='finance_transaction_context' AND ab.module_id='soldi' AND ab.entity_kind='transaction' AND ab.canonical_id=t.uuid AND cb.module_id='places' AND cb.entity_kind='place' LIMIT 1)) AS place,
          COALESCE(pf.value,(SELECT cf.value FROM hub_contexts hc JOIN hub_context_members am ON am.context_id=hc.id JOIN hub_entity_bindings ab ON ab.id=am.entity_id JOIN hub_context_members cm ON cm.context_id=hc.id JOIN hub_entity_bindings cb ON cb.id=cm.entity_id JOIN contacts pc ON pc.public_id=cb.canonical_id JOIN contact_fields cf ON cf.id=(SELECT id FROM contact_fields WHERE contact_id=pc.id AND field_type='name' ORDER BY is_primary DESC,position,id LIMIT 1) WHERE hc.context_type_id='finance_transaction_context' AND ab.module_id='soldi' AND ab.entity_kind='transaction' AND ab.canonical_id=t.uuid AND cb.module_id='people' AND cb.entity_kind='person' LIMIT 1)) AS person,
          COALESCE((SELECT ht.name FROM hub_entity_bindings hb JOIN hub_tag_assignments ha ON ha.target_binding_id=hb.id JOIN hub_tags ht ON ht.id=ha.tag_id WHERE hb.module_id='soldi' AND hb.entity_kind='transaction' AND hb.canonical_id=t.uuid AND ht.namespace='soldi.category' LIMIT 1),'') AS category
        FROM finance_transactions t
        LEFT JOIN finance_products p ON p.id=t.productId
        LEFT JOIN finance_titles n ON n.id=t.titleId
        LEFT JOIN finance_chains c ON c.id=t.chainId
        LEFT JOIN places l ON l.uuid=t.placeId
        LEFT JOIN contact_fields pf ON pf.id=(SELECT id FROM contact_fields WHERE contact_id=t.personId AND field_type='name' ORDER BY is_primary DESC, position ASC, id ASC LIMIT 1)
        WHERE t.occurredAt >= :fromMs AND t.occurredAt < :toMs
        ORDER BY t.occurredAt DESC,t.uuid DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun temporalTransactionViews(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<TransactionView>

    @Query("SELECT name FROM finance_titles WHERE id=:id") suspend fun titleName(id: Long): String?
    @Query("SELECT name FROM finance_chains WHERE id=:id") suspend fun chainName(id: Long): String?
    @Insert suspend fun add(value: FinanceAccount)
    @Update suspend fun update(value: FinanceAccount)
    @Query("SELECT * FROM finance_products ORDER BY name") fun products(): Flow<List<FinanceProduct>>
    @Query("SELECT uuid AS id, nickname AS name FROM places WHERE archived=0 ORDER BY nickname") fun places(): Flow<List<PlaceChoice>>

    @Query("""
        SELECT c.id AS id, c.public_id AS publicId,
               COALESCE(
                   (SELECT value FROM contact_fields f WHERE f.contact_id=c.id AND f.field_type='name' ORDER BY f.is_primary DESC, f.position ASC, f.id ASC LIMIT 1),
                   (SELECT value FROM contact_fields f WHERE f.contact_id=c.id AND f.field_type='nickname' ORDER BY f.is_primary DESC, f.position ASC, f.id ASC LIMIT 1),
                   'Person'
               ) AS name
        FROM contacts c
        WHERE c.deleted_at IS NULL AND c.archived_at IS NULL
        ORDER BY name COLLATE NOCASE, c.id
    """)
    fun people(): Flow<List<PersonChoice>>

    @Query("SELECT * FROM finance_transactions WHERE id=:id") suspend fun transaction(id: Long): FinanceTransaction?
    @Query("SELECT * FROM finance_transactions WHERE recurrenceId=:recurrenceId AND occurrenceKey=:occurrenceKey ORDER BY id LIMIT 1") suspend fun transactionForOccurrence(recurrenceId: String, occurrenceKey: String): FinanceTransaction?
    @Query("SELECT * FROM finance_transactions WHERE macroId=:macroId ORDER BY occurredAt,id") suspend fun macroChildren(macroId: String): List<FinanceTransaction>
    @Query("SELECT * FROM finance_stores WHERE placeId=:id") suspend fun store(id: String): FinanceStore?
    @Query("SELECT COUNT(*) FROM finance_transactions WHERE placeId=:id") suspend fun transactionCountForPlace(id: String): Int
    @Query("SELECT COUNT(*) FROM finance_stores WHERE placeId=:id") suspend fun storeCountForPlace(id: String): Int
    @Query("SELECT id FROM finance_products WHERE name=:name") suspend fun productId(name: String): Long?
    @Query("SELECT id FROM finance_titles WHERE name=:name") suspend fun titleId(name: String): Long?
    @Query("SELECT id FROM finance_chains WHERE name=:name") suspend fun chainId(name: String): Long?

    @Insert suspend fun add(value: FinanceProduct): Long
    @Insert suspend fun add(value: FinanceTitle): Long
    @Insert suspend fun add(value: FinanceChain): Long
    @Insert suspend fun add(value: FinanceStore)
    @Insert suspend fun add(value: FinanceTransaction): Long
    @Update suspend fun update(value: FinanceTransaction)
    @Update suspend fun update(value: FinanceProduct)
    @Query("DELETE FROM finance_transactions WHERE id=:id") suspend fun deleteTransaction(id: Long)
    @Query("DELETE FROM finance_products WHERE id=:id") suspend fun deleteProduct(id: Long)

    @Query("SELECT * FROM finance_transfers ORDER BY createdAt DESC") fun transfers(): Flow<List<FinanceTransfer>>
    @Query("SELECT * FROM finance_transfers ORDER BY createdAt DESC") suspend fun allTransfers(): List<FinanceTransfer>
    @Query("SELECT * FROM finance_transfers WHERE id=:id") suspend fun transfer(id: String): FinanceTransfer?
    @Query("SELECT * FROM finance_transfers WHERE sourceTransactionId=:transactionId OR targetTransactionId=:transactionId LIMIT 1") suspend fun transferForTransaction(transactionId: Long): FinanceTransfer?
    @Insert suspend fun add(value: FinanceTransfer)
    @Update suspend fun update(value: FinanceTransfer)
    @Query("DELETE FROM finance_transfers WHERE id=:id") suspend fun deleteTransfer(id: String)

    @Query("SELECT * FROM finance_macros ORDER BY occurredAt DESC,id DESC") fun macros(): Flow<List<FinanceMacro>>
    @Query("SELECT * FROM finance_macros WHERE id=:id") suspend fun macro(id: String): FinanceMacro?
    @Insert suspend fun add(value: FinanceMacro)
    @Query("DELETE FROM finance_macros WHERE id=:id") suspend fun deleteMacro(id: String)

    @Query("SELECT * FROM finance_recurrences ORDER BY enabled DESC,startDate,title") fun recurrences(): Flow<List<FinanceRecurrence>>
    @Query("""
        SELECT r.*,COALESCE((SELECT ht.name FROM hub_entity_bindings hb JOIN hub_tag_assignments ha ON ha.target_binding_id=hb.id JOIN hub_tags ht ON ht.id=ha.tag_id WHERE hb.module_id='soldi' AND hb.entity_kind='recurrence' AND hb.canonical_id=r.id AND ht.namespace='soldi.category' LIMIT 1),'') AS category
        FROM finance_recurrences r ORDER BY r.enabled DESC,r.startDate,r.title
    """) fun recurrenceViews(): Flow<List<FinanceRecurrenceView>>
    @Query("SELECT * FROM finance_recurrences WHERE id=:id") suspend fun recurrence(id: String): FinanceRecurrence?
    @Query("SELECT * FROM finance_recurrences WHERE enabled=1") suspend fun enabledRecurrences(): List<FinanceRecurrence>
    @Insert suspend fun add(value: FinanceRecurrence)
    @Update suspend fun update(value: FinanceRecurrence)
    @Query("DELETE FROM finance_recurrences WHERE id=:id") suspend fun deleteRecurrence(id: String)

    @Query("SELECT * FROM finance_recurrence_overrides WHERE recurrenceId=:recurrenceId AND occurrenceDate=:occurrenceDate") suspend fun recurrenceOverride(recurrenceId: String, occurrenceDate: String): FinanceRecurrenceOverride?
    @Query("SELECT * FROM finance_recurrence_overrides WHERE recurrenceId=:recurrenceId ORDER BY occurrenceDate") suspend fun recurrenceOverrides(recurrenceId: String): List<FinanceRecurrenceOverride>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: FinanceRecurrenceOverride)

    @Query("SELECT * FROM finance_attachments ORDER BY transactionId,createdAt,id") fun allAttachments(): Flow<List<FinanceAttachment>>
    @Query("SELECT * FROM finance_attachments WHERE transactionId=:transactionId ORDER BY createdAt,id") fun attachments(transactionId: Long): Flow<List<FinanceAttachment>>
    @Query("SELECT * FROM finance_attachments WHERE transactionId=:transactionId ORDER BY createdAt,id") suspend fun attachmentsOnce(transactionId: Long): List<FinanceAttachment>
    @Insert suspend fun add(value: FinanceAttachment)
    @Query("DELETE FROM finance_attachments WHERE id=:id") suspend fun deleteAttachment(id: String)


    @Query("SELECT * FROM finance_photo_index ORDER BY transactionId,attachmentId") fun photoIndexes(): Flow<List<FinancePhotoIndex>>
    @Query("SELECT * FROM finance_photo_index ORDER BY transactionId,attachmentId") suspend fun photoIndexesOnce(): List<FinancePhotoIndex>
    @Query("SELECT * FROM finance_photo_index WHERE attachmentId=:attachmentId") suspend fun photoIndex(attachmentId: String): FinancePhotoIndex?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPhotoIndex(value: FinancePhotoIndex)
    @Query("DELETE FROM finance_photo_index WHERE attachmentId=:attachmentId") suspend fun deletePhotoIndex(attachmentId: String)

    @Query("SELECT * FROM finance_owned_items WHERE archivedAt IS NULL AND disposedAt IS NULL ORDER BY updatedAt DESC,uuid")
    fun ownedItems(): Flow<List<FinanceOwnedItem>>
    @Query("SELECT * FROM finance_owned_items WHERE sourceTransactionId=:transactionId AND archivedAt IS NULL AND disposedAt IS NULL ORDER BY updatedAt DESC,uuid")
    suspend fun ownedItemsForTransaction(transactionId: Long): List<FinanceOwnedItem>
    @Query("SELECT * FROM finance_owned_items WHERE uuid=:uuid") suspend fun ownedItem(uuid: String): FinanceOwnedItem?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOwnedItem(value: FinanceOwnedItem)
    @Query("DELETE FROM finance_owned_items WHERE uuid=:uuid") suspend fun deleteOwnedItem(uuid: String)
}
