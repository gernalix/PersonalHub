package com.gernalix.personalhub.core.database.capsules.soldi

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gernalix.luoghi.data.PlaceEntity

@Entity(tableName = "finance_products", indices = [Index(value = ["name"], unique = true), Index(value = ["uuid"], unique = true)])
data class FinanceProduct(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "''") val uuid: String = java.util.UUID.randomUUID().toString(),
)

@Entity(tableName = "finance_titles", indices = [Index(value = ["name"], unique = true)])
data class FinanceTitle(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "finance_chains", indices = [Index(value = ["name"], unique = true)])
data class FinanceChain(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "finance_stores",
    indices = [Index("chainId")],
    foreignKeys = [
        ForeignKey(entity = PlaceEntity::class, parentColumns = ["uuid"], childColumns = ["placeId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FinanceChain::class, parentColumns = ["id"], childColumns = ["chainId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class FinanceStore(@PrimaryKey val placeId: String, val chainId: Long?)

@Entity(
    tableName = "finance_transactions",
    indices = [
        Index("accountId"), Index(value = ["uuid"], unique = true), Index("titleId"), Index("productId"),
        Index("chainId"), Index("placeId"), Index("occurredAt"), Index("personId"), Index("macroId"),
        Index("recurrenceId"), Index(value = ["recurrenceId", "occurrenceKey"], unique = false),
        Index("reminderAt"), Index("category"),
    ],
    foreignKeys = [
        ForeignKey(entity = FinanceAccount::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FinanceTitle::class, parentColumns = ["id"], childColumns = ["titleId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FinanceProduct::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FinanceChain::class, parentColumns = ["id"], childColumns = ["chainId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PlaceEntity::class, parentColumns = ["uuid"], childColumns = ["placeId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class FinanceTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val titleId: Long?,
    val productId: Long?,
    val amount: String,
    val currency: String,
    val chainId: Long?,
    val placeId: String?,
    val fromReceipt: Boolean = false,
    val notes: String,
    val occurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val personId: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val macroId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val recurrenceId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val occurrenceKey: String? = null,
    @ColumnInfo(defaultValue = "NULL") val reminderAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val category: String = "",
)

@Entity(tableName = "finance_tags", indices = [Index(value = ["name"], unique = true)])
data class FinanceTag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "finance_transaction_tags",
    primaryKeys = ["transactionId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = FinanceTransaction::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FinanceTag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class FinanceTransactionTag(val transactionId: Long, val tagId: Long)

@Entity(
    tableName = "finance_transfers",
    indices = [Index(value = ["sourceTransactionId"], unique = true), Index(value = ["targetTransactionId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = FinanceTransaction::class, parentColumns = ["id"], childColumns = ["sourceTransactionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FinanceTransaction::class, parentColumns = ["id"], childColumns = ["targetTransactionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FinanceTransfer(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sourceTransactionId: Long,
    val targetTransactionId: Long,
    val quotedRate: String? = null,
    val feeAmount: String? = null,
    val feeCurrency: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "finance_macros",
    indices = [Index("accountId"), Index("occurredAt")],
    foreignKeys = [ForeignKey(entity = FinanceAccount::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)],
)
data class FinanceMacro(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val accountId: String,
    val currency: String,
    val occurredAt: Long,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "finance_recurrences",
    indices = [Index("accountId"), Index("targetAccountId"), Index("enabled"), Index("startDate"), Index("category")],
    foreignKeys = [ForeignKey(entity = FinanceAccount::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)],
)
data class FinanceRecurrence(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val amount: String,
    val currency: String,
    val accountId: String,
    val personId: Long? = null,
    val chain: String = "",
    val placeId: String? = null,
    val notes: String = "",
    val dayOfMonth: Int? = null,
    val lastBusinessDay: Boolean = false,
    val startDate: String,
    val endDate: String? = null,
    val reminderDaysBefore: Int? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val kind: String = "EXPENSE",
    val targetAccountId: String? = null,
    val targetAmount: String? = null,
    val quotedRate: String? = null,
    val feeAmount: String? = null,
    val feeCurrency: String? = null,
    val category: String = "",
)

@Entity(
    tableName = "finance_recurrence_tags",
    primaryKeys = ["recurrenceId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = FinanceRecurrence::class, parentColumns = ["id"], childColumns = ["recurrenceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FinanceTag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class FinanceRecurrenceTag(val recurrenceId: String, val tagId: Long)

@Entity(
    tableName = "finance_recurrence_overrides",
    primaryKeys = ["recurrenceId", "occurrenceDate"],
    indices = [Index("transactionId")],
    foreignKeys = [
        ForeignKey(entity = FinanceRecurrence::class, parentColumns = ["id"], childColumns = ["recurrenceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FinanceTransaction::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class FinanceRecurrenceOverride(
    val recurrenceId: String,
    val occurrenceDate: String,
    val amount: String? = null,
    val targetAmount: String? = null,
    val skipped: Boolean = false,
    val transactionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "finance_attachments",
    indices = [Index("transactionId")],
    foreignKeys = [ForeignKey(entity = FinanceTransaction::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE)],
)
data class FinanceAttachment(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val transactionId: Long,
    val kind: String,
    val uri: String,
    val title: String = "",
    val mimeType: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class TransactionView(
    @Embedded val value: FinanceTransaction,
    val title: String,
    val chain: String?,
    val place: String?,
    val person: String? = null,
)

data class PlaceChoice(val id: String, val name: String)
data class PersonChoice(val id: Long, val publicId: String?, val name: String)

@Entity(tableName = "finance_accounts")
data class FinanceAccount(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val currency: String,
    val openingBalance: String = "0",
    val openedAt: Long = 0L,
    val included: Boolean = true,
)

data class TagTotalRow(val name: String, val total: String)
