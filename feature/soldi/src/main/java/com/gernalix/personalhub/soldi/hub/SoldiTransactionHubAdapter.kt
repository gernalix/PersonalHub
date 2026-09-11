package com.gernalix.personalhub.soldi.hub

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.*
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

private const val FINANCE_CURSOR_SEPARATOR = '\u001F'
private data class FinanceTemporalRow(val occurredAt: String, val record: HubTemporalRecord)

class SoldiTransactionHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider {
    override val moduleId = "soldi"
    override val entityKind = "transaction"
    override val capabilities = setOf("finance", "transaction")
    private val database get() = PersonalHubDatabase.get(context)
    private val dao get() = database.financeDao()

    override suspend fun exists(canonicalId: String) = dao.transactionByUuid(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = if (canonicalIds.isEmpty()) emptyMap() else
        dao.transactionViewsByUuid(canonicalIds.toList()).associate { it.value.uuid to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchTransactionViews(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/soldi?transactionUuid=${android.net.Uri.encode(canonicalId)}", "com.gernalix.personalhub.soldi.SoldiActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage = withContext(Dispatchers.IO) {
        val cursor = decodeFinanceCursor(query.cursor)
        val fromIso = Instant.ofEpochMilli(query.fromMs).toString()
        val toIso = Instant.ofEpochMilli(query.toMs).toString()
        val args = mutableListOf<Any?>(fromIso, toIso)
        val cursorClause = if (cursor != null) {
            args += cursor.first
            args += cursor.first
            args += cursor.second
            "AND (t.occurredAt < ? OR (t.occurredAt = ? AND t.uuid < ?))"
        } else ""
        args += query.limit + 1
        val sql = """
            SELECT t.uuid, t.occurredAt,
                   COALESCE(p.name, n.name, '') AS title,
                   t.notes, t.amount, t.currency
            FROM finance_transactions t
            LEFT JOIN finance_products p ON p.id = t.productId
            LEFT JOIN finance_titles n ON n.id = t.titleId
            WHERE t.occurredAt >= ? AND t.occurredAt < ?
              $cursorClause
            ORDER BY t.occurredAt DESC, t.uuid DESC
            LIMIT ?
        """.trimIndent()
        val rows = database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val uuid = c.getString(0)
                    val occurredAt = c.getString(1)
                    val title = c.getString(2).orEmpty()
                    val notes = c.getString(3).orEmpty()
                    val amount = c.getString(4)
                    val currency = c.getString(5)
                    val label = title.ifBlank { notes.ifBlank { "$amount $currency" } }
                    add(
                        FinanceTemporalRow(
                            occurredAt,
                            HubTemporalRecord(
                                moduleId,
                                entityKind,
                                uuid,
                                HubTemporalKind.POINT,
                                Instant.parse(occurredAt).toEpochMilli(),
                                title = label,
                                subtitle = "$amount $currency",
                                entityRef = HubEntityRef(moduleId, entityKind, uuid),
                            ),
                        ),
                    )
                }
            }
        }
        val pageRows = rows.take(query.limit)
        HubTemporalPage(
            pageRows.map { it.record },
            if (rows.size > query.limit) pageRows.lastOrNull()?.let { encodeFinanceCursor(it.occurredAt, it.record.stableId) } else null,
        )
    }

    private fun TransactionView.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, value.uuid),
        title.ifBlank { value.notes.ifBlank { "${value.amount} ${value.currency}" } },
        "${value.amount} ${value.currency} · ${value.occurredAt}", attributes = buildMap { put("time_ms", java.time.Instant.parse(value.occurredAt).toEpochMilli().toString()); value.placeId?.let { put("place_id", it) } },
    )
}

private fun encodeFinanceCursor(occurredAt: String, uuid: String): String = buildString {
    append(occurredAt)
    append(FINANCE_CURSOR_SEPARATOR)
    append(uuid)
}

private fun decodeFinanceCursor(value: String?): Pair<String, String>? {
    if (value.isNullOrBlank()) return null
    val separator = value.indexOf(FINANCE_CURSOR_SEPARATOR)
    if (separator <= 0 || separator >= value.lastIndex) return null
    return value.substring(0, separator) to value.substring(separator + 1)
}
