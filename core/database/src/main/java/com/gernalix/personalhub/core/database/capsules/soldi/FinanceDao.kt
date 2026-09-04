package com.gernalix.personalhub.core.database.capsules.soldi

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceDao {
    @Query("SELECT t.*, COALESCE(p.name,n.name,'') AS title,c.name AS chain,l.nickname AS place FROM finance_transactions t LEFT JOIN finance_products p ON p.id=t.productId LEFT JOIN finance_titles n ON n.id=t.titleId LEFT JOIN finance_chains c ON c.id=t.chainId LEFT JOIN places l ON l.uuid=t.placeId ORDER BY t.occurredAt DESC,t.id DESC")
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
    @Query("SELECT name FROM finance_titles WHERE id=:id") suspend fun titleName(id: Long): String?
    @Query("SELECT name FROM finance_chains WHERE id=:id") suspend fun chainName(id: Long): String?
    @Insert suspend fun add(value: FinanceAccount)
    @Update suspend fun update(value: FinanceAccount)
    @Query("SELECT name FROM finance_tags ORDER BY name") fun tagNames(): Flow<List<String>>
    @Query("SELECT * FROM finance_products ORDER BY name") fun products(): Flow<List<FinanceProduct>>
    @Query("SELECT uuid AS id, nickname AS name FROM places WHERE archived=0 ORDER BY nickname") fun places(): Flow<List<PlaceChoice>>
    @Query("SELECT * FROM finance_transactions WHERE id=:id") suspend fun transaction(id: Long): FinanceTransaction?
    @Query("SELECT * FROM finance_stores WHERE placeId=:id") suspend fun store(id: String): FinanceStore?
    @Query("SELECT name FROM finance_tags JOIN finance_transaction_tags ON id=tagId WHERE transactionId=:id ORDER BY name") suspend fun tags(id: Long): List<String>
    @Query("SELECT id FROM finance_products WHERE name=:name") suspend fun productId(name: String): Long?
    @Query("SELECT id FROM finance_titles WHERE name=:name") suspend fun titleId(name: String): Long?
    @Query("SELECT id FROM finance_chains WHERE name=:name") suspend fun chainId(name: String): Long?
    @Query("SELECT id FROM finance_tags WHERE name=:name") suspend fun tagId(name: String): Long?
    @Insert suspend fun add(value: FinanceProduct): Long
    @Insert suspend fun add(value: FinanceTitle): Long
    @Insert suspend fun add(value: FinanceChain): Long
    @Insert suspend fun add(value: FinanceStore)
    @Insert suspend fun add(value: FinanceTransaction): Long
    @Insert suspend fun add(value: FinanceTag): Long
    @Insert suspend fun add(value: FinanceTransactionTag)
    @Update suspend fun update(value: FinanceTransaction)
    @Update suspend fun update(value: FinanceProduct)
    @Query("DELETE FROM finance_transactions WHERE id=:id") suspend fun deleteTransaction(id: Long)
    @Query("DELETE FROM finance_transaction_tags WHERE transactionId=:id") suspend fun deleteTags(id: Long)
    @Query("DELETE FROM finance_products WHERE id=:id") suspend fun deleteProduct(id: Long)
}
