package com.gernalix.personalhub.core.database.capsules.soldi

import android.content.Context
import androidx.room.withTransaction
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.SharedTagEngine
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Currency

/** Finance boundary: all Soldi writes commit atomically through the shared PersonalHub database. */
class FinanceCapsule(private val db: PersonalHubDatabase) {
    constructor(context: Context) : this(PersonalHubDatabase.get(context))

    private val dao = db.financeDao()
    private val sharedTags = SharedTagEngine(db)
    private val writesGlobalHubContext: Boolean
        get() = db.openHelper.databaseName == PersonalHubDatabase.DATABASE_NAME
    val accounts = dao.accounts()
    val transactions = dao.transactions()
    val products = dao.products()
    val places = dao.places()
    val people = dao.people()
    val tagNames = sharedTags.observe(HubTagNamespaces.SOLDI).map { values -> values.filterNot { it.archived }.map { it.name } }
    val transfers = dao.transfers()
    val macros = dao.macros()
    val recurrences = dao.recurrenceViews().map { values -> values.map { view -> view.value.apply { category = view.category } } }
    val allAttachments = dao.allAttachments()
    val photoIndexes = dao.photoIndexes()
    val ownedItems = dao.ownedItems()

    suspend fun tags(id: Long) = dao.transaction(id)?.let { value ->
        sharedTags.tags(HubEntityRef("soldi", "transaction", value.uuid))
            .filter { it.namespace == HubTagNamespaces.SOLDI }
            .map { it.name }
    }.orEmpty()
    suspend fun recurrenceTags(id: String) = sharedTags.tags(HubEntityRef("soldi", "recurrence", id))
        .filter { it.namespace == HubTagNamespaces.SOLDI }
        .map { it.name }
    suspend fun contextRefs(entityKind: String, canonicalId: String): Set<HubEntityRef> =
        HubContextRuntime.linked(HubEntityRef("soldi", entityKind, canonicalId))
            .map { it.ref }
            .filterNot { it.moduleId == "tags" }
            .toSet()
    suspend fun category(ref: HubEntityRef): String = sharedTags.tags(ref)
        .firstOrNull { it.namespace == HubTagNamespaces.SOLDI_CATEGORY }?.name.orEmpty()

    suspend fun spendingByTag(fromMs: Long, toMs: Long): List<FinanceTagSpend> {
        require(fromMs <= toMs)
        val totals = linkedMapOf<Triple<String, String, String>, BigDecimal>()
        dao.allTransactions()
            .asSequence()
            .filter { it.occurredAt in fromMs until toMs }
            .forEach { transaction ->
                val amount = BigDecimal(transaction.amount)
                if (amount.signum() >= 0) return@forEach
                sharedTags.tags(HubEntityRef("soldi", "transaction", transaction.uuid))
                    .filter { it.namespace == HubTagNamespaces.SOLDI || it.namespace == HubTagNamespaces.SOLDI_CATEGORY }
                    .distinctBy { it.id }
                    .forEach { tag ->
                        val key = Triple(tag.namespace, tag.name, transaction.currency)
                        totals[key] = (totals[key] ?: BigDecimal.ZERO) + amount.abs()
                    }
            }
        return totals.map { (key, total) ->
            FinanceTagSpend(key.first, key.second, key.third, total.stripTrailingZeros().toPlainString())
        }.sortedWith(compareByDescending<FinanceTagSpend> { BigDecimal(it.amount) }.thenBy { it.tagName })
    }

    private suspend fun replaceCategory(ref: HubEntityRef, name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) {
            val current = sharedTags.tags(ref).filter { it.namespace == HubTagNamespaces.SOLDI_CATEGORY }
            sharedTags.remove(ref, current.map { it.id })
            return
        }
        val tag = sharedTags.search(HubTagNamespaces.SOLDI_CATEGORY, cleaned).firstOrNull {
            SharedTagEngine.normalize(it.name) == SharedTagEngine.normalize(cleaned)
        } ?: requireNotNull(
            sharedTags.create(
                HubTagNamespaces.SOLDI_CATEGORY,
                cleaned,
                kind = com.gernalix.personalhub.contracts.database.HubTagKinds.CATEGORY,
                acceptNearDuplicate = true,
            ).tag,
        )
        sharedTags.replace(ref, HubTagNamespaces.SOLDI_CATEGORY, listOf(tag.id))
    }
    fun attachments(transactionId: Long) = dao.attachments(transactionId)

    suspend fun reuseLatestTitle(draft: TransactionDraft, title: String): TransactionDraft = db.withTransaction {
        val previous = dao.transactionsWithTitle(title).maxWithOrNull(
            compareBy<FinanceTransaction> { it.occurredAt }.thenBy { it.id },
        ) ?: return@withTransaction draft.copy(title = title)
        draft.copy(
            title = title,
            isProduct = false,
            productId = null,
            amount = previous.amount,
            currency = previous.currency,
            accountId = previous.accountId,
            chain = previous.chainId?.let { dao.chainName(it) }.orEmpty(),
            placeId = previous.placeId,
            personId = previous.personId,
            category = category(HubEntityRef("soldi", "transaction", previous.uuid)),
            notes = previous.notes,
            tags = tags(previous.id).joinToString(", "),
            fromReceipt = previous.fromReceipt,
        )
    }

    private suspend fun product(name: String): Long {
        val normalized = name.trim()
        require(normalized.isNotEmpty())
        return dao.productId(normalized) ?: dao.add(FinanceProduct(name = normalized))
    }

    private suspend fun title(name: String): Long {
        val normalized = name.trim()
        require(normalized.isNotEmpty())
        return dao.titleId(normalized) ?: dao.add(FinanceTitle(name = normalized))
    }

    private suspend fun chain(name: String): Long? = name.trim().takeIf(String::isNotEmpty)?.let {
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
        val now = System.currentTimeMillis()
        val chainId = chain(draft.chain)
        store(draft.placeId, chainId)
        val account = draft.accountId?.let { requireNotNull(dao.account(it)) } ?: defaultAccount(draft.currency)
        require(account.currency == currency(draft.currency))
        require(epoch(draft.occurredAt) >= account.openedAt)
        val selectedProduct = draft.productId?.let { requireNotNull(dao.product(it)) }
        val value = FinanceTransaction(
            id = old?.id ?: 0,
            accountId = account.id,
            uuid = old?.uuid ?: draft.transactionUuid ?: java.util.UUID.randomUUID().toString(),
            titleId = if (draft.isProduct || selectedProduct != null) null else title(draft.title),
            productId = selectedProduct?.id ?: if (draft.isProduct) product(draft.title) else null,
            amount = decimal(draft.amount),
            currency = currency(draft.currency),
            chainId = chainId,
            placeId = null,
            fromReceipt = draft.fromReceipt,
            notes = draft.notes,
            occurredAt = epoch(draft.occurredAt),
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            personId = null,
            macroId = draft.macroId,
            recurrenceId = draft.recurrenceId,
            occurrenceKey = draft.occurrenceKey,
            reminderAt = draft.reminderAt?.takeIf(String::isNotBlank)?.let(::epoch),
        )
        val id = if (old == null) dao.add(value) else {
            dao.update(value)
            old.id
        }
        sharedTags.replaceByNames(
            HubEntityRef("soldi", "transaction", value.uuid),
            HubTagNamespaces.SOLDI,
            draft.tags.split(','),
        )
        replaceCategory(HubEntityRef("soldi", "transaction", value.uuid), draft.category)
        if (writesGlobalHubContext) {
            HubContextRuntime.saveFinanceTransactionLinksIfInitialized(
                transactionUuid = value.uuid,
                personPublicId = draft.personId?.let { db.contactsDao().getContactEntity(it)?.publicId },
                placeId = draft.placeId,
                contextualRefs = draft.contextRefs,
            )
        }
        id
    }

    /** Two ledger legs plus one transfer record form a single logical transfer. */
    suspend fun saveTransfer(draft: TransferDraft): FinanceTransfer = db.withTransaction {
        val source = requireNotNull(dao.account(draft.sourceAccountId))
        val target = requireNotNull(dao.account(draft.targetAccountId))
        require(source.id != target.id)
        val sourceAmount = BigDecimal(decimal(draft.sourceAmount)).abs()
        val targetAmount = BigDecimal(decimal(draft.targetAmount)).abs()
        require(sourceAmount.signum() > 0 && targetAmount.signum() > 0)
        val old = draft.transferId?.let { dao.transfer(it) }
        val sourceId = saveTransaction(
            TransactionDraft(
                id = old?.sourceTransactionId,
                title = draft.title.ifBlank { "Transfer" },
                amount = sourceAmount.negate().stripTrailingZeros().toPlainString(),
                currency = source.currency,
                accountId = source.id,
                notes = draft.notes,
                tags = draft.tags,
                personId = draft.personId,
                category = draft.category,
                occurredAt = draft.occurredAt,
                reminderAt = draft.reminderAt,
                recurrenceId = draft.recurrenceId,
                occurrenceKey = draft.occurrenceKey,
                contextRefs = draft.contextRefs,
            ),
        )
        val targetId = saveTransaction(
            TransactionDraft(
                id = old?.targetTransactionId,
                title = draft.title.ifBlank { "Transfer" },
                amount = targetAmount.stripTrailingZeros().toPlainString(),
                currency = target.currency,
                accountId = target.id,
                notes = draft.notes,
                tags = draft.tags,
                personId = draft.personId,
                category = draft.category,
                occurredAt = draft.occurredAt,
                recurrenceId = draft.recurrenceId,
                occurrenceKey = draft.occurrenceKey,
                contextRefs = draft.contextRefs,
            ),
        )
        val value = FinanceTransfer(
            id = old?.id ?: draft.transferId ?: java.util.UUID.randomUUID().toString(),
            sourceTransactionId = sourceId,
            targetTransactionId = targetId,
            quotedRate = draft.quotedRate?.takeIf(String::isNotBlank)?.let(::decimal),
            feeAmount = draft.feeAmount?.takeIf(String::isNotBlank)?.let(::decimal),
            feeCurrency = draft.feeCurrency?.takeIf(String::isNotBlank)?.let(::currency),
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
        )
        if (old == null) dao.add(value) else dao.update(value)
        value
    }

    /** Receipt import creates one non-posting macro and leaf postings that alone affect balances. */
    suspend fun importReceipt(value: FinanceReceiptImport): List<FinanceReceiptImportResult> = db.withTransaction {
        require(value.items.isNotEmpty())
        val occurredAt = utc(value.occurredAt)
        val receiptCurrency = currency(value.currency)
        val account = value.accountId?.let { requireNotNull(dao.account(it)) } ?: defaultAccount(receiptCurrency)
        val macro = FinanceMacro(
            title = value.merchant.ifBlank { "Receipt" },
            accountId = account.id,
            currency = receiptCurrency,
            occurredAt = epoch(occurredAt),
        )
        dao.add(macro)
        value.items.map { item ->
            val selected = item.productId?.let { requireNotNull(dao.product(it)) }
            val productId = selected?.id ?: product(item.productName)
            val canonicalProduct = requireNotNull(dao.product(productId))
            val amount = BigDecimal(decimal(item.totalPrice)).negate().stripTrailingZeros().toPlainString()
            val transactionId = saveTransaction(
                TransactionDraft(
                    title = canonicalProduct.name,
                    isProduct = true,
                    amount = amount,
                    currency = receiptCurrency,
                    chain = value.merchant,
                    category = value.category,
                    fromReceipt = true,
                    occurredAt = occurredAt,
                    accountId = account.id,
                    productId = canonicalProduct.id,
                    macroId = macro.id,
                ),
            )
            FinanceReceiptImportResult(item.rawDescription, canonicalProduct, transactionId)
        }
    }

    suspend fun deleteTransaction(id: Long) {
        val deletedUuids = db.withTransaction {
            val transfer = dao.transferForTransaction(id)
            val ids = if (transfer == null) listOf(id) else listOf(transfer.sourceTransactionId, transfer.targetTransactionId)
            val uuids = ids.mapNotNull { dao.transaction(it)?.uuid }
            uuids.forEach { sharedTags.clear(HubEntityRef("soldi", "transaction", it)) }
            transfer?.let { dao.deleteTransfer(it.id) }
            ids.distinct().forEach { dao.deleteTransaction(it) }
            uuids
        }
        deletedUuids.forEach {
            com.gernalix.personalhub.core.hubcontext.HubContextRuntime.canonicalDeletedIfInitialized(
                com.gernalix.personalhub.contracts.database.HubEntityRef("soldi", "transaction", it),
            )
        }
    }

    suspend fun deleteMacro(id: String) = db.withTransaction {
        dao.macroChildren(id).forEach { dao.deleteTransaction(it.id) }
        dao.deleteMacro(id)
    }

    suspend fun saveProduct(id: Long?, name: String) = db.withTransaction {
        if (id == null) product(name) else {
            require(name.isNotBlank())
            dao.update(requireNotNull(dao.product(id)).copy(name = name.trim()))
            id
        }
    }

    suspend fun deleteProduct(id: Long) = db.withTransaction { dao.deleteProduct(id) }

    private suspend fun defaultAccount(code: String): FinanceAccount {
        val normalized = currency(code)
        val id = java.util.UUID.nameUUIDFromBytes(("finance-default:" + normalized).toByteArray()).toString()
        return dao.account(id) ?: FinanceAccount(id = id, name = normalized, currency = normalized).also { dao.add(it) }
    }

    suspend fun saveAccount(value: FinanceAccount): String = db.withTransaction {
        require(value.name.isNotBlank())
        val account = value.copy(
            name = value.name.trim(),
            currency = currency(value.currency),
            openingBalance = decimal(value.openingBalance),
            openedAt = value.openedAt,
        )
        val rows = dao.allTransactions().filter { it.accountId == account.id }
        require(rows.all { it.currency == account.currency && it.occurredAt >= account.openedAt })
        if (dao.account(account.id) == null) dao.add(account) else dao.update(account)
        account.id
    }

    suspend fun setIncluded(id: String, included: Boolean) = db.withTransaction {
        dao.update(requireNotNull(dao.account(id)).copy(included = included))
    }

    suspend fun reconcile(id: String, at: String, desired: String, title: String, notes: String): Long? = db.withTransaction {
        val account = requireNotNull(dao.account(id))
        val instant = Instant.parse(utc(at))
        require(instant.toEpochMilli() >= account.openedAt)
        val difference = BigDecimal(decimal(desired)) - balance(account, dao.allTransactions(), instant)
        if (difference.signum() == 0) null else saveTransaction(
            TransactionDraft(
                title = title,
                amount = difference.toPlainString(),
                currency = account.currency,
                notes = notes,
                tags = "reconciliation",
                occurredAt = instant.toString(),
                accountId = id,
            ),
        )
    }

    suspend fun addAttachment(transactionId: Long, draft: AttachmentDraft): FinanceAttachment = db.withTransaction {
        requireNotNull(dao.transaction(transactionId))
        require(draft.uri.isNotBlank())
        FinanceAttachment(
            transactionId = transactionId,
            kind = draft.kind,
            uri = draft.uri.trim(),
            title = draft.title.trim(),
            mimeType = draft.mimeType,
        ).also { dao.add(it) }
    }

    suspend fun deleteAttachment(id: String) = db.withTransaction { dao.deleteAttachment(id) }

    suspend fun photoIndex(attachmentId: String): FinancePhotoIndex? = dao.photoIndex(attachmentId)

    suspend fun putPhotoIndex(value: FinancePhotoIndex) = db.withTransaction {
        requireNotNull(dao.transaction(value.transactionId))
        dao.putPhotoIndex(value)
    }

    suspend fun ownedItemsForTransaction(transactionId: Long): List<FinanceOwnedItem> =
        dao.ownedItemsForTransaction(transactionId)

    suspend fun ownedItem(uuid: String): FinanceOwnedItem? = dao.ownedItem(uuid)

    suspend fun trackOwnedItem(
        transactionId: Long,
        name: String,
        primaryAttachmentId: String? = null,
    ): FinanceOwnedItem = db.withTransaction {
        requireNotNull(dao.transaction(transactionId))
        val normalized = name.trim().ifBlank { "Oggetto" }
        val attachmentId = primaryAttachmentId?.takeIf { candidate ->
            dao.attachmentsOnce(transactionId).any { it.id == candidate }
        }
        val item = FinanceOwnedItem(
            sourceTransactionId = transactionId,
            name = normalized,
            primaryAttachmentId = attachmentId,
        )
        dao.putOwnedItem(item)
        item
    }

    suspend fun removeOwnedItem(uuid: String) = db.withTransaction { dao.deleteOwnedItem(uuid) }

    suspend fun saveRecurrence(draft: RecurrenceDraft): String = db.withTransaction {
        val kind = recurrenceKind(draft.kind)
        val account = requireNotNull(dao.account(draft.accountId))
        val sourceAbs = BigDecimal(decimal(draft.amount)).abs()
        require(sourceAbs.signum() > 0)
        require(account.currency == currency(draft.currency))
        require((draft.dayOfMonth != null) xor draft.lastBusinessDay)
        draft.dayOfMonth?.let { require(it in 1..31) }
        draft.reminderDaysBefore?.let { require(it >= 0) }
        val start = LocalDate.parse(draft.startDate)
        val end = draft.endDate?.takeIf(String::isNotBlank)?.let(LocalDate::parse)
        require(end == null || !end.isBefore(start))

        val target = if (kind == "TRANSFER") requireNotNull(draft.targetAccountId).let { requireNotNull(dao.account(it)) } else null
        if (target != null) require(target.id != account.id)
        val normalizedAmount = when (kind) {
            "EXPENSE", "TRANSFER" -> sourceAbs.negate().stripTrailingZeros().toPlainString()
            else -> sourceAbs.stripTrailingZeros().toPlainString()
        }
        val normalizedTarget = if (kind == "TRANSFER") {
            BigDecimal(decimal(requireNotNull(draft.targetAmount))).abs().also { require(it.signum() > 0) }.stripTrailingZeros().toPlainString()
        } else null

        val now = System.currentTimeMillis()
        val old = draft.id?.let { dao.recurrence(it) }
        val value = FinanceRecurrence(
            id = old?.id ?: draft.id ?: java.util.UUID.randomUUID().toString(),
            title = draft.title.trim().also { require(it.isNotBlank()) },
            amount = normalizedAmount,
            currency = account.currency,
            accountId = account.id,
            personId = null,
            chain = draft.chain.trim(),
            placeId = null,
            notes = draft.notes,
            dayOfMonth = draft.dayOfMonth,
            lastBusinessDay = draft.lastBusinessDay,
            startDate = start.toString(),
            endDate = end?.toString(),
            reminderDaysBefore = draft.reminderDaysBefore,
            enabled = draft.enabled,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            kind = kind,
            targetAccountId = target?.id,
            targetAmount = normalizedTarget,
            quotedRate = draft.quotedRate?.takeIf(String::isNotBlank)?.let(::decimal),
            feeAmount = draft.feeAmount?.takeIf(String::isNotBlank)?.let(::decimal),
            feeCurrency = draft.feeCurrency?.takeIf(String::isNotBlank)?.let(::currency),
        )
        if (old == null) dao.add(value) else dao.update(value)
        sharedTags.replaceByNames(
            HubEntityRef("soldi", "recurrence", value.id),
            HubTagNamespaces.SOLDI,
            draft.tags.split(','),
        )
        replaceCategory(HubEntityRef("soldi", "recurrence", value.id), draft.category)
        if (writesGlobalHubContext) {
            HubContextRuntime.saveFinanceRecurrenceLinksIfInitialized(
                value.id,
                draft.personId?.let { db.contactsDao().getContactEntity(it)?.publicId },
                draft.placeId,
                draft.contextRefs,
            )
        }
        value.id
    }

    suspend fun setRecurrenceEnabled(id: String, enabled: Boolean) = db.withTransaction {
        val row = requireNotNull(dao.recurrence(id))
        dao.update(row.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteRecurrence(id: String) = db.withTransaction {
        sharedTags.clear(HubEntityRef("soldi", "recurrence", id))
        dao.deleteRecurrence(id)
    }

    suspend fun editRecurrenceAmount(
        id: String,
        occurrenceDate: LocalDate,
        amount: String,
        targetAmount: String? = null,
        scope: RecurrenceEditScope,
    ): String = db.withTransaction {
        val row = requireNotNull(dao.recurrence(id))
        val sourceAbs = BigDecimal(decimal(amount)).abs()
        val normalized = if (row.kind == "INCOME") sourceAbs.toPlainString() else sourceAbs.negate().toPlainString()
        val normalizedTarget = if (row.kind == "TRANSFER") {
            BigDecimal(decimal(targetAmount ?: requireNotNull(row.targetAmount))).abs().stripTrailingZeros().toPlainString()
        } else null
        when (scope) {
            RecurrenceEditScope.ONLY_THIS -> {
                dao.put(FinanceRecurrenceOverride(id, occurrenceDate.toString(), amount = normalized, targetAmount = normalizedTarget))
                id
            }
            RecurrenceEditScope.THIS_AND_FOLLOWING -> {
                val start = LocalDate.parse(row.startDate)
                if (!occurrenceDate.isAfter(start)) {
                    dao.update(row.copy(amount = normalized, targetAmount = normalizedTarget ?: row.targetAmount, startDate = occurrenceDate.toString(), updatedAt = System.currentTimeMillis()))
                    id
                } else {
                    val originalEnd = row.endDate
                    dao.update(row.copy(endDate = occurrenceDate.minusDays(1).toString(), updatedAt = System.currentTimeMillis()))
                    val newId = java.util.UUID.randomUUID().toString()
                    dao.add(
                        row.copy(
                            id = newId,
                            amount = normalized,
                            targetAmount = normalizedTarget ?: row.targetAmount,
                            startDate = occurrenceDate.toString(),
                            endDate = originalEnd,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    sharedTags.copy(
                        HubEntityRef("soldi", "recurrence", id),
                        HubEntityRef("soldi", "recurrence", newId),
                    )
                    newId
                }
            }
        }
    }

    private suspend fun materializedOccurrenceInstant(rule: FinanceRecurrence, date: LocalDate): String? {
        val zone = ZoneId.systemDefault()
        val accountIds = buildList {
            add(rule.accountId)
            if (rule.kind == "TRANSFER") add(requireNotNull(rule.targetAccountId))
        }
        val latestOpening = accountIds
            .map { id -> Instant.ofEpochMilli(requireNotNull(dao.account(id)).openedAt) }
            .maxOrNull()
            ?: return null
        if (latestOpening.atZone(zone).toLocalDate().isAfter(date)) return null
        return maxOf(date.atStartOfDay(zone).toInstant(), latestOpening).toString()
    }

    /** Materialize occurrences through today. Future occurrences remain projections until due. */
    suspend fun materializeDueRecurrences(today: LocalDate = LocalDate.now()): List<Long> = db.withTransaction {
        val created = mutableListOf<Long>()
        for (rule in dao.enabledRecurrences()) {
            val ruleCategory = category(HubEntityRef("soldi", "recurrence", rule.id))
            val start = LocalDate.parse(rule.startDate)
            val end = rule.endDate?.let(LocalDate::parse)?.let { minOf(it, today) } ?: today
            if (end.isBefore(start)) continue
            for (date in occurrenceDates(rule, start, end)) {
                val key = date.toString()
                if (dao.transactionForOccurrence(rule.id, key) != null) continue
                val override = dao.recurrenceOverride(rule.id, key)
                if (override?.skipped == true) continue
                val occurredAt = materializedOccurrenceInstant(rule, date) ?: continue
                if (rule.kind == "TRANSFER") {
                    val transfer = saveTransfer(
                        TransferDraft(
                            title = rule.title,
                            sourceAccountId = rule.accountId,
                            targetAccountId = requireNotNull(rule.targetAccountId),
                            sourceAmount = override?.amount ?: rule.amount,
                            targetAmount = override?.targetAmount ?: requireNotNull(rule.targetAmount),
                            quotedRate = rule.quotedRate,
                            feeAmount = rule.feeAmount,
                            feeCurrency = rule.feeCurrency,
                            notes = rule.notes,
                            tags = recurrenceTags(rule.id).joinToString(", "),
                            personId = rule.personId,
                            category = ruleCategory,
                            occurredAt = occurredAt,
                            recurrenceId = rule.id,
                            occurrenceKey = key,
                        ),
                    )
                    dao.put(FinanceRecurrenceOverride(rule.id, key, amount = override?.amount, targetAmount = override?.targetAmount, skipped = false, transactionId = transfer.sourceTransactionId))
                    created += transfer.sourceTransactionId
                    created += transfer.targetTransactionId
                } else {
                    val id = saveTransaction(
                        TransactionDraft(
                            title = rule.title,
                            amount = override?.amount ?: rule.amount,
                            currency = rule.currency,
                            accountId = rule.accountId,
                            chain = rule.chain,
                            placeId = rule.placeId,
                            personId = rule.personId,
                            category = ruleCategory,
                            notes = rule.notes,
                            tags = recurrenceTags(rule.id).joinToString(", "),
                            occurredAt = occurredAt,
                            recurrenceId = rule.id,
                            occurrenceKey = key,
                        ),
                    )
                    dao.put(FinanceRecurrenceOverride(rule.id, key, amount = override?.amount, skipped = false, transactionId = id))
                    created += id
                }
            }
        }
        created
    }

    suspend fun projectedOccurrences(from: LocalDate, to: LocalDate): List<ProjectedOccurrence> = db.withTransaction {
        require(!to.isBefore(from))
        dao.enabledRecurrences().flatMap { rule ->
            occurrenceDates(rule, from, to).flatMap { date ->
                val override = dao.recurrenceOverride(rule.id, date.toString())
                if (override?.skipped == true) return@flatMap emptyList()
                val materializedId = override?.transactionId ?: dao.transactionForOccurrence(rule.id, date.toString())?.id
                if (rule.kind == "TRANSFER") {
                    val target = requireNotNull(rule.targetAccountId).let { requireNotNull(dao.account(it)) }
                    listOf(
                        ProjectedOccurrence(rule.id, rule.title, override?.amount ?: rule.amount, rule.currency, rule.accountId, date, materializedId, "SOURCE"),
                        ProjectedOccurrence(rule.id, rule.title, override?.targetAmount ?: requireNotNull(rule.targetAmount), target.currency, target.id, date, materializedId, "TARGET"),
                    )
                } else {
                    listOf(ProjectedOccurrence(rule.id, rule.title, override?.amount ?: rule.amount, rule.currency, rule.accountId, date, materializedId, "SINGLE"))
                }
            }
        }.sortedWith(compareBy<ProjectedOccurrence> { it.date }.thenBy { it.title }.thenBy { it.leg })
    }

    suspend fun nextOccurrence(rule: FinanceRecurrence, after: LocalDate = LocalDate.now()): LocalDate? {
        for (date in occurrenceDates(rule, after, after.plusYears(2))) {
            val override = dao.recurrenceOverride(rule.id, date.toString())
            if (override?.skipped == true) continue
            if (dao.transactionForOccurrence(rule.id, date.toString()) == null) return date
        }
        return null
    }

    companion object {
        fun balance(account: FinanceAccount, rows: List<FinanceTransaction>, at: Instant = Instant.now()): BigDecimal {
            if (at.toEpochMilli() < account.openedAt) return BigDecimal.ZERO
            return rows.filter { it.accountId == account.id && it.occurredAt <= at.toEpochMilli() }
                .fold(BigDecimal(account.openingBalance)) { sum, row -> sum + BigDecimal(row.amount) }
        }

        fun totals(accounts: List<FinanceAccount>, rows: List<FinanceTransaction>, at: Instant = Instant.now()): Map<String, BigDecimal> =
            accounts.filter { it.included }.groupBy { it.currency }.toSortedMap().mapValues { (_, group) ->
                group.fold(BigDecimal.ZERO) { sum, account -> sum + balance(account, rows, at) }
            }

        fun projectedTotals(
            accounts: List<FinanceAccount>,
            rows: List<FinanceTransaction>,
            at: Instant,
            projected: List<ProjectedOccurrence>,
        ): Map<String, BigDecimal> {
            val base = totals(accounts, rows, at).toMutableMap()
            val includedIds = accounts.filter { it.included }.map { it.id }.toSet()
            projected.filter { it.accountId in includedIds && it.materializedTransactionId == null }.forEach { occurrence ->
                base[occurrence.currency] = (base[occurrence.currency] ?: BigDecimal.ZERO) + BigDecimal(occurrence.amount)
            }
            return base.toSortedMap()
        }

        fun effectiveRate(sourceAmount: String, targetAmount: String): BigDecimal {
            val source = BigDecimal(decimal(sourceAmount)).abs()
            val target = BigDecimal(decimal(targetAmount)).abs()
            require(source.signum() != 0)
            return target.divide(source, 8, RoundingMode.HALF_UP).stripTrailingZeros()
        }

        fun decimal(input: String): String {
            val value = input.trim().replace(',', '.')
            require(value.matches(Regex("[+-]?[0-9]+(\\.[0-9]+)?")))
            return BigDecimal(value).stripTrailingZeros().toPlainString()
        }

        fun currency(input: String): String = input.trim().uppercase(java.util.Locale.ROOT).also { Currency.getInstance(it) }
        fun utc(input: String): String = Instant.parse(input.trim()).toString()
        fun epoch(input: String): Long = Instant.parse(input.trim()).toEpochMilli()

        fun occurrenceDate(rule: FinanceRecurrence, month: YearMonth): LocalDate = if (rule.lastBusinessDay) {
            var date = month.atEndOfMonth()
            while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) date = date.minusDays(1)
            date
        } else {
            month.atDay(minOf(requireNotNull(rule.dayOfMonth), month.lengthOfMonth()))
        }

        fun occurrenceDates(rule: FinanceRecurrence, from: LocalDate, to: LocalDate): List<LocalDate> {
            if (to.isBefore(from)) return emptyList()
            val start = maxOf(from, LocalDate.parse(rule.startDate))
            val boundedTo = rule.endDate?.let(LocalDate::parse)?.let { minOf(to, it) } ?: to
            if (boundedTo.isBefore(start)) return emptyList()
            val firstMonth = YearMonth.from(start)
            val lastMonth = YearMonth.from(boundedTo)
            val months = ChronoUnit.MONTHS.between(firstMonth, lastMonth).toInt()
            return (0..months)
                .map { occurrenceDate(rule, firstMonth.plusMonths(it.toLong())) }
                .filter { !it.isBefore(start) && !it.isAfter(boundedTo) }
        }

        fun recurrenceKind(value: String): String = value.trim().uppercase().also { require(it in setOf("EXPENSE", "INCOME", "TRANSFER")) }
    }
}

data class TransactionDraft(
    val id: Long? = null,
    val transactionUuid: String? = null,
    val title: String = "",
    val isProduct: Boolean = false,
    val amount: String = "",
    val currency: String = "DKK",
    val chain: String = "",
    val placeId: String? = null,
    val notes: String = "",
    val tags: String = "",
    val fromReceipt: Boolean = false,
    val occurredAt: String = Instant.now().toString(),
    val accountId: String? = null,
    val productId: Long? = null,
    val personId: Long? = null,
    val macroId: String? = null,
    val recurrenceId: String? = null,
    val occurrenceKey: String? = null,
    val reminderAt: String? = null,
    val category: String = "",
    val contextRefs: Set<HubEntityRef> = emptySet(),
)

data class TransferDraft(
    val transferId: String? = null,
    val title: String = "Transfer",
    val sourceAccountId: String,
    val targetAccountId: String,
    val sourceAmount: String,
    val targetAmount: String,
    val quotedRate: String? = null,
    val feeAmount: String? = null,
    val feeCurrency: String? = null,
    val notes: String = "",
    val tags: String = "",
    val personId: Long? = null,
    val category: String = "",
    val occurredAt: String = Instant.now().toString(),
    val reminderAt: String? = null,
    val recurrenceId: String? = null,
    val occurrenceKey: String? = null,
    val contextRefs: Set<HubEntityRef> = emptySet(),
)

data class RecurrenceDraft(
    val id: String? = null,
    val title: String,
    val amount: String,
    val currency: String,
    val accountId: String,
    val personId: Long? = null,
    val chain: String = "",
    val placeId: String? = null,
    val notes: String = "",
    val tags: String = "",
    val dayOfMonth: Int? = null,
    val lastBusinessDay: Boolean = false,
    val startDate: String = LocalDate.now().toString(),
    val endDate: String? = null,
    val reminderDaysBefore: Int? = null,
    val enabled: Boolean = true,
    val kind: String = "EXPENSE",
    val targetAccountId: String? = null,
    val targetAmount: String? = null,
    val quotedRate: String? = null,
    val feeAmount: String? = null,
    val feeCurrency: String? = null,
    val category: String = "",
    val contextRefs: Set<HubEntityRef> = emptySet(),
)

enum class RecurrenceEditScope { ONLY_THIS, THIS_AND_FOLLOWING }

data class ProjectedOccurrence(
    val recurrenceId: String,
    val title: String,
    val amount: String,
    val currency: String,
    val accountId: String,
    val date: LocalDate,
    val materializedTransactionId: Long?,
    val leg: String,
)

data class AttachmentDraft(val kind: String, val uri: String, val title: String = "", val mimeType: String? = null)

data class FinanceTagSpend(val namespace: String, val tagName: String, val currency: String, val amount: String)

data class FinanceReceiptImport(
    val merchant: String,
    val occurredAt: String,
    val currency: String,
    val accountId: String?,
    val items: List<FinanceReceiptImportItem>,
    val category: String = "",
)

data class FinanceReceiptImportItem(val rawDescription: String, val productName: String, val productId: Long?, val totalPrice: String)

data class FinanceReceiptImportResult(val rawDescription: String, val product: FinanceProduct, val transactionId: Long)
