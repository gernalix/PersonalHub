package com.gernalix.personalhub.soldi.hub

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView

class SoldiTransactionHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "soldi"
    override val entityKind = "transaction"
    override val capabilities = setOf("finance", "transaction")
    private val dao get() = PersonalHubDatabase.get(context).financeDao()

    override suspend fun exists(canonicalId: String) = dao.transactionByUuid(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = if (canonicalIds.isEmpty()) emptyMap() else
        dao.transactionViewsByUuid(canonicalIds.toList()).associate { it.value.uuid to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchTransactionViews(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/soldi?transactionUuid=${android.net.Uri.encode(canonicalId)}", "com.gernalix.personalhub.soldi.SoldiActivity")

    private fun TransactionView.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, value.uuid),
        title.ifBlank { value.notes.ifBlank { "${value.amount} ${value.currency}" } },
        "${value.amount} ${value.currency} · ${value.occurredAt}",
    )
}
