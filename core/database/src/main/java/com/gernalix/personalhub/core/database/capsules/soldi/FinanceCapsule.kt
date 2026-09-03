package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import androidx.room.withTransaction
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/** Finance boundary: all writes, dimensions and links commit atomically through PH's writer gate. */
class FinanceCapsule(private val db: PersonalHubDatabase) {
    constructor(context: Context) : this(PersonalHubDatabase.get(context))
    private val dao = db.financeDao()
    val transactions = dao.transactions()
    val products = dao.products()
    val places = dao.places()
    suspend fun tags(id: Long) = dao.tags(id)

    private suspend fun product(name: String): Long {
        val n = name.trim(); require(n.isNotEmpty())
        return dao.productId(n) ?: dao.add(FinanceProduct(name = n))
    }
    private suspend fun title(name: String): Long {
        val n = name.trim(); require(n.isNotEmpty())
        return dao.titleId(n) ?: dao.add(FinanceTitle(name = n))
    }
    private suspend fun chain(name: String): Long? = name.trim().takeIf { it.isNotEmpty() }?.let {
        dao.chainId(it) ?: dao.add(FinanceChain(name = it))
    }
    private suspend fun store(placeId: String?, chainId: Long?) {
        if (placeId == null) return
        val existing = dao.store(placeId)
        if (existing == null) dao.add(FinanceStore(placeId, chainId))
        else require(existing.chainId == chainId) { "Store chain differs" }
    }

    suspend fun saveTransaction(draft: TransactionDraft): Long = db.withTransaction {
        val old = draft.id?.let { requireNotNull(dao.transaction(it)) }
        val now = Instant.now().toString()
        val chainId = chain(draft.chain)
        store(draft.placeId, chainId)
        val value = FinanceTransaction(
            id = old?.id ?: 0,
            titleId = if (draft.isProduct) null else title(draft.title),
            productId = if (draft.isProduct) product(draft.title) else null,
            amount = decimal(draft.amount), currency = currency(draft.currency),
            chainId = chainId, placeId = draft.placeId, fromReceipt = draft.fromReceipt, notes = draft.notes,
            occurredAt = utc(draft.occurredAt), createdAt = old?.createdAt ?: now, updatedAt = now
        )
        val id = if (old == null) dao.add(value) else { dao.update(value); old.id }
        dao.deleteTags(id)
        draft.tags.split(',').map(String::trim).filter(String::isNotEmpty).distinct().forEach { name ->
            val tag = dao.tagId(name) ?: dao.add(FinanceTag(name = name))
            dao.add(FinanceTransactionTag(id, tag))
        }
        id
    }
    suspend fun deleteTransaction(id: Long) = db.withTransaction { dao.deleteTransaction(id) }
    suspend fun saveProduct(id: Long?, name: String) = db.withTransaction {
        if (id == null) product(name) else {
            require(name.isNotBlank()); dao.update(FinanceProduct(id, name.trim())); id
        }
    }
    suspend fun deleteProduct(id: Long) = db.withTransaction { dao.deleteProduct(id) }

    companion object {
        fun decimal(input: String): String {
            val value = input.trim().replace(',', '.')
            require(value.matches(Regex("[+-]?[0-9]+(\\.[0-9]+)?")))
            return BigDecimal(value).stripTrailingZeros().toPlainString()
        }
        fun currency(input: String): String = input.trim().uppercase(java.util.Locale.ROOT).also { Currency.getInstance(it) }
        fun utc(input: String): String = Instant.parse(input.trim()).toString()
    }
}

data class TransactionDraft(val id: Long? = null, val title: String = "", val isProduct: Boolean = false,
    val amount: String = "", val currency: String = "DKK", val chain: String = "", val placeId: String? = null,
    val notes: String = "", val tags: String = "", val fromReceipt: Boolean = false, val occurredAt: String = Instant.now().toString())