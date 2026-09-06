package com.gernalix.personalhub.core.database.capsules.soldi

import androidx.room.*
import com.gernalix.luoghi.data.PlaceEntity

@Entity(tableName = "finance_products", indices = [Index(value = ["name"], unique = true), Index(value = ["uuid"], unique = true)])
data class FinanceProduct(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, @ColumnInfo(defaultValue = "''") val uuid: String = java.util.UUID.randomUUID().toString())

@Entity(tableName = "finance_titles", indices = [Index(value = ["name"], unique = true)])
data class FinanceTitle(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "finance_chains", indices = [Index(value = ["name"], unique = true)])
data class FinanceChain(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

// Stores are finance attributes of existing PH Places, never another place catalog.
@Entity(tableName = "finance_stores", indices = [Index("chainId")], foreignKeys = [
    ForeignKey(entity = PlaceEntity::class, parentColumns = ["uuid"], childColumns = ["placeId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = FinanceChain::class, parentColumns = ["id"], childColumns = ["chainId"], onDelete = ForeignKey.RESTRICT)
])
data class FinanceStore(@PrimaryKey val placeId: String, val chainId: Long?)

@Entity(tableName = "finance_transactions", indices = [Index("accountId"), Index(value = ["uuid"], unique = true), Index("titleId"), Index("productId"), Index("chainId"), Index("placeId"), Index("occurredAt")], foreignKeys = [
    ForeignKey(entity = FinanceAccount::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = FinanceTitle::class, parentColumns = ["id"], childColumns = ["titleId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = FinanceProduct::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = FinanceChain::class, parentColumns = ["id"], childColumns = ["chainId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = PlaceEntity::class, parentColumns = ["uuid"], childColumns = ["placeId"], onDelete = ForeignKey.RESTRICT)
])
data class FinanceTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String, val uuid: String = java.util.UUID.randomUUID().toString(),
    val titleId: Long?, val productId: Long?, val amount: String, val currency: String,
    val chainId: Long?, val placeId: String?, val fromReceipt: Boolean = false,
    val notes: String, val occurredAt: String, val createdAt: String, val updatedAt: String
)

@Entity(tableName = "finance_tags", indices = [Index(value = ["name"], unique = true)])
data class FinanceTag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "finance_transaction_tags", primaryKeys = ["transactionId", "tagId"], indices = [Index("tagId")], foreignKeys = [
    ForeignKey(entity = FinanceTransaction::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = FinanceTag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.RESTRICT)
])
data class FinanceTransactionTag(val transactionId: Long, val tagId: Long)

data class TransactionView(@Embedded val value: FinanceTransaction, val title: String, val chain: String?, val place: String?)
data class PlaceChoice(val id: String, val name: String)

@Entity(tableName = "finance_accounts")
data class FinanceAccount(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String, val currency: String, val openingBalance: String = "0",
    val openedAt: String = "1970-01-01T00:00:00Z", val included: Boolean = true
)
