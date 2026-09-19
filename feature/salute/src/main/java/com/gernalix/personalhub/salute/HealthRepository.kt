package com.gernalix.personalhub.salute

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HealthTimelineItem(
    val data: String?, val dataMs: Long, val tipo: String, val titolo: String,
    val sottotitolo: String?, val valore: String?, val flag: String?,
    val tempoReferto: String?, val dettaglio: String?, val commentoAi: String?,
    val sourceKind: String, val sourceId: String, val sampleId: String?,
)

data class HealthMeasurementHistoryItem(
    val sourceId: String, val data: String?, val dataMs: Long, val esame: String,
    val numericValue: Double?, val risultato: String?, val unita: String?,
    val flag: String?, val tempoReferto: String?, val commentoAi: String?,
)

data class HealthJournalDetail(
    val sourceId: String, val data: String?, val dataMs: Long?, val tipo: String?,
    val reparto: String?, val struttura: String?, val clinico: String?, val ruolo: String?,
    val titolo: String?, val nota: String, val commentoAi: String?,
    val incertezzeAi: String?, val originaleDa: String, val stanceAi: String?,
)

data class HealthEvidence(val kind: String, val id: String, val label: String?, val relevance: String)
data class HealthSampleDetail(
    val id: String, val date: String?, val kind: String, val resultCount: Int,
    val abnormalCount: Int, val commentAi: String?, val sourceComment: String?, val results: List<HealthTimelineItem>,
)

data class HealthTurnaroundSummary(val count: Int, val averageMs: Long) {
    val formatted: String get() = "${averageMs / 86_400_000L}g ${(averageMs % 86_400_000L) / 3_600_000L}h"
}

data class HealthSnapshot(val timeline: List<HealthTimelineItem>, val turnaround: HealthTurnaroundSummary?)
sealed interface HealthLoadResult {
    data class Ready(val snapshot: HealthSnapshot) : HealthLoadResult
    data class Error(val message: String) : HealthLoadResult
}

/** All reads use the canonical Room-owned personalhub.db. No feature-local database or sync state. */
class HealthRepository(context: Context) {
    private val app = context.applicationContext
    private val database = PersonalHubDatabase.get(app)
    private val formatter = DateTimeFormatter.ofPattern("d-M-yy")
    private fun date(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(formatter) }
    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun query(sql: String, vararg args: Any?, block: (Cursor) -> Unit) {
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use(block)
    }

    suspend fun load(forceRefresh: Boolean = false): HealthLoadResult = withContext(Dispatchers.IO) {
        runCatching {
            LegacyHealthImport.importCached(app, database)
            val timeline = mutableListOf<HealthTimelineItem>()
            query("""
                SELECT t.data_ms,t.kind,t.entity_id,t.title,t.subtitle,t.value,t.unit,t.flag,
                       t.ai_comment,turn.delta_ms,t.sample_id
                FROM v_health_timeline t
                LEFT JOIN v_health_measurement_turnaround turn ON t.kind='measurement' AND turn.measurement_id=t.entity_id
                LEFT JOIN health_samples s ON s.id=t.sample_id
                LEFT JOIN health_events e ON e.id=s.event_id
                ORDER BY t.data_ms DESC,t.kind,t.entity_id
            """.trimIndent()) { c ->
                while (c.moveToNext()) {
                    val ms = if (c.isNull(0)) 0L else c.getLong(0)
                    val latency = if (c.isNull(9)) null else c.getLong(9)
                    timeline += HealthTimelineItem(date(if (c.isNull(0)) null else ms), ms,
                        c.getString(1), c.getString(3), c.stringOrNull(4),
                        c.stringOrNull(5)?.let { v -> c.stringOrNull(6)?.let { "$v $it" } ?: v },
                        c.stringOrNull(7), latency?.let { "${it / 86_400_000L}g ${(it % 86_400_000L) / 3_600_000L}h" },
                        null, c.stringOrNull(8), c.getString(1), c.getString(2), c.stringOrNull(10))
                }
            }
            var summary: HealthTurnaroundSummary? = null
            query("SELECT COUNT(delta_ms),AVG(delta_ms) FROM v_health_measurement_turnaround") { c ->
                if (c.moveToFirst() && c.getInt(0)>0 && !c.isNull(1)) summary=HealthTurnaroundSummary(c.getInt(0),c.getDouble(1).toLong())
            }
            HealthLoadResult.Ready(HealthSnapshot(timeline, summary))
        }.getOrElse { HealthLoadResult.Error(it.message ?: "Impossibile leggere i dati Salute") }
    }

    suspend fun measurementHistory(sourceId: String): List<HealthMeasurementHistoryItem> = withContext(Dispatchers.IO) {
        val rows=mutableListOf<HealthMeasurementHistoryItem>()
        query("""
            SELECT v.id,v.data_ms,v.examination_name,v.numeric_value,
                   COALESCE(v.text_value,v.interpretation_it),v.unit,v.flag,t.delta_ms,v.ai_comment
            FROM v_health_measurements v
            LEFT JOIN v_health_measurement_turnaround t ON t.measurement_id=v.id
            WHERE v.examination_id=(SELECT examination_id FROM health_measurements WHERE id=? LIMIT 1)
            ORDER BY v.data_ms DESC,v.id
        """.trimIndent(),sourceId) { c ->
            while(c.moveToNext()) {
                val ms=if(c.isNull(1)) 0L else c.getLong(1)
                val latency=if(c.isNull(7)) null else c.getLong(7)
                rows+=HealthMeasurementHistoryItem(c.getString(0),date(ms),ms,c.getString(2),
                    if(c.isNull(3)) null else c.getDouble(3),c.stringOrNull(4),c.stringOrNull(5),
                    c.stringOrNull(6),latency?.let { "${it/86_400_000L}g ${(it%86_400_000L)/3_600_000L}h" },c.stringOrNull(8))
            }
        }
        rows
    }

    suspend fun sampleDetail(sourceId: String): HealthSampleDetail? = withContext(Dispatchers.IO) {
        var result: HealthSampleDetail?=null
        query("SELECT sample_id,data_ms,sample_kind,result_count,abnormal_count,ai_comment FROM v_health_samples WHERE sample_id=?",sourceId) { c ->
            if(c.moveToFirst()) result=HealthSampleDetail(c.getString(0),date(if(c.isNull(1)) null else c.getLong(1)),c.getString(2),c.getInt(3),c.getInt(4),c.stringOrNull(5),null,emptyList())
        }
        var sourceComment: String?=null
        query("SELECT value FROM health_source_metadata WHERE owner_kind='sample' AND owner_id=? AND key LIKE 'legacy.comment.%.comment_it' ORDER BY key LIMIT 1",sourceId) { c -> if(c.moveToFirst()) sourceComment=c.getString(0) }
        result?.copy(sourceComment=sourceComment,results=load().let { (it as? HealthLoadResult.Ready)?.snapshot?.timeline?.filter { row -> row.sourceKind=="measurement" && row.sampleId==sourceId }.orEmpty() })
    }

    suspend fun journalDetail(sourceId: String): HealthJournalDetail? = withContext(Dispatchers.IO) {
        var result: HealthJournalDetail?=null
        query("""
            SELECT j.id,j.data_ms,j.encounter_type_it,j.department_it,p.nickname,
                   (SELECT f.value FROM contact_fields f WHERE f.contact_id=j.clinician_contact_id
                    AND f.field_type IN ('name','nickname') ORDER BY CASE f.field_type WHEN 'name' THEN 0 ELSE 1 END, f.position LIMIT 1),
                   j.clinician_role_it,j.title_it,j.text_it,j.ai_comment,
                   j.ai_uncertainty,j.original_text_da,j.ai_stance
            FROM v_health_journal j
            LEFT JOIN places p ON p.uuid=j.place_id
            WHERE j.id=? LIMIT 1
        """.trimIndent(),sourceId) { c ->
            if(c.moveToFirst()) result=HealthJournalDetail(c.getString(0),date(if(c.isNull(1)) null else c.getLong(1)),
                if(c.isNull(1)) null else c.getLong(1),c.stringOrNull(2),c.stringOrNull(3),c.stringOrNull(4),
                c.stringOrNull(5),c.stringOrNull(6),c.stringOrNull(7),c.getString(8),c.stringOrNull(9),c.stringOrNull(10),c.getString(11),c.stringOrNull(12))
        }
        result
    }

    suspend fun journalEvidence(sourceId: String): List<HealthEvidence> = withContext(Dispatchers.IO) {
        val rows=mutableListOf<HealthEvidence>()
        query("SELECT evidence_kind,evidence_id,relevance_it FROM v_health_ai_evidence WHERE subject_kind='journal' AND subject_id=? ORDER BY position",sourceId) { c ->
            while(c.moveToNext()) rows+=HealthEvidence(c.getString(0),c.getString(1),null,c.getString(2))
        }
        rows
    }
}
