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
    val accounts = dao.accounts()
    val transactions = dao.transactions()
    val products = dao.products()
    val places = dao.places()
    val tagNames = dao.tagNames()
    suspend fun tags(id: Long) = dao.tags(id)

    /** Reuse the most recent occurrence, preserving the draft's identity and selected date. */
    suspend fun reuseLatestTitle(draft: TransactionDraft, title: String): TransactionDraft = db.withTransaction {
        val previous = dao.transactionsWithTitle(title).maxWithOrNull(
            compareBy<FinanceTransaction> { Instant.parse(it.occurredAt) }.thenBy { it.id }
        ) ?: return@withTransaction draft.copy(title = title)
        draft.copy(title = title, isProduct = false, productId = null,
            amount = previous.amount, currency = previous.currency, accountId = previous.accountId,
            chain = previous.chainId?.let { dao.chainName(it) }.orEmpty(), placeId = previous.placeId,
            notes = previous.notes, tags = dao.tags(previous.id).joinToString(", "), fromReceipt = previous.fromReceipt)
    }

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
        val account = draft.accountId?.let { requireNotNull(dao.account(it)) } ?: defaultAccount(draft.currency)
        require(account.currency == currency(draft.currency))
        require(Instant.parse(utc(draft.occurredAt)) >= Instant.parse(account.openedAt))
        val selectedProduct = draft.productId?.let { requireNotNull(dao.product(it)) }
        val value = FinanceTransaction(
            id = old?.id ?: 0,
            accountId = account.id, uuid = old?.uuid ?: java.util.UUID.randomUUID().toString(),
            titleId = if (draft.isProduct || selectedProduct != null) null else title(draft.title),
            productId = selectedProduct?.id ?: if (draft.isProduct) product(draft.title) else null,
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
            require(name.isNotBlank()); dao.update(requireNotNull(dao.product(id)).copy(name = name.trim())); id
        }
    }
    suspend fun deleteProduct(id: Long) = db.withTransaction { dao.deleteProduct(id) }

    private suspend fun defaultAccount(code: String): FinanceAccount {
        val c = currency(code)
        val id = java.util.UUID.nameUUIDFromBytes(("finance-default:" + c).toByteArray()).toString()
        return dao.account(id) ?: FinanceAccount(id = id, name = c, currency = c).also { dao.add(it) }
    }
    suspend fun saveAccount(value: FinanceAccount): String = db.withTransaction {
        require(value.name.isNotBlank())
        val account = value.copy(name = value.name.trim(), currency = currency(value.currency), openingBalance = decimal(value.openingBalance), openedAt = utc(value.openedAt))
        val rows = dao.allTransactions().filter { it.accountId == account.id }
        require(rows.all { it.currency == account.currency && Instant.parse(it.occurredAt) >= Instant.parse(account.openedAt) })
        if (dao.account(account.id) == null) dao.add(account) else dao.update(account)
        account.id
    }
    suspend fun setIncluded(id: String, included: Boolean) = db.withTransaction {
        dao.update(requireNotNull(dao.account(id)).copy(included = included))
    }
    suspend fun reconcile(id: String, at: String, desired: String, title: String, notes: String): Long? = db.withTransaction {
        val account = requireNotNull(dao.account(id)); val instant = Instant.parse(utc(at))
        require(instant >= Instant.parse(account.openedAt))
        val difference = BigDecimal(decimal(desired)) - balance(account, dao.allTransactions(), instant)
        if (difference.signum() == 0) null else saveTransaction(TransactionDraft(
            title = title, amount = difference.toPlainString(), currency = account.currency, notes = notes,
            tags = "reconciliation", occurredAt = instant.toString(), accountId = id))
    }

    companion object {
        fun balance(account: FinanceAccount, rows: List<FinanceTransaction>, at: Instant = Instant.now()): BigDecimal {
            if (at < Instant.parse(account.openedAt)) return BigDecimal.ZERO
            return rows.filter { it.accountId == account.id && Instant.parse(it.occurredAt) <= at }
                .fold(BigDecimal(account.openingBalance)) { sum, row -> sum + BigDecimal(row.amount) }
        }
        fun totals(accounts: List<FinanceAccount>, rows: List<FinanceTransaction>, at: Instant = Instant.now()): Map<String, BigDecimal> =
            accounts.filter { it.included }.groupBy { it.currency }.toSortedMap().mapValues { (_, group) ->
                group.fold(BigDecimal.ZERO) { sum, account -> sum + balance(account, rows, at) }
            }

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
    val notes: String = "", val tags: String = "", val fromReceipt: Boolean = false, val occurredAt: String = Instant.now().toString(), val accountId: String? = null, val productId: Long? = null)
