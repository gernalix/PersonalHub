package com.gernalix.personalhub.soldi.hub

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.*
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView

class SoldiTransactionHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider {
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

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage {
        val offset = query.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rows = dao.temporalTransactionViews(java.time.Instant.ofEpochMilli(query.fromMs).toString(), java.time.Instant.ofEpochMilli(query.toMs).toString(), query.limit + 1, offset)
        return HubTemporalPage(rows.take(query.limit).map { it.temporal() }, if (rows.size > query.limit) (offset + query.limit).toString() else null)
    }

    private fun TransactionView.temporal() = HubTemporalRecord(moduleId, entityKind, value.uuid, HubTemporalKind.POINT, java.time.Instant.parse(value.occurredAt).toEpochMilli(), title = title.ifBlank { value.notes.ifBlank { "${value.amount} ${value.currency}" } }, subtitle = "${value.amount} ${value.currency}", entityRef = HubEntityRef(moduleId, entityKind, value.uuid))

    private fun TransactionView.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, value.uuid),
        title.ifBlank { value.notes.ifBlank { "${value.amount} ${value.currency}" } },
        "${value.amount} ${value.currency} · ${value.occurredAt}", attributes = buildMap { put("time_ms", java.time.Instant.parse(value.occurredAt).toEpochMilli().toString()); value.placeId?.let { put("place_id", it) } },
    )
}
