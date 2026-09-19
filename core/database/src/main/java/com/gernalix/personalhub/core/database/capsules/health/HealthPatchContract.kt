package com.gernalix.personalhub.core.database.capsules.health

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray

/** Final transaction gate for health patch invariants that cannot be expressed as Room FKs. */
object HealthPatchContract {
    fun validate(db: SupportSQLiteDatabase, operations: JSONArray) {
        val subjects=linkedSetOf<Pair<String,String>>()
        var touchesHealth=false
        for (i in 0 until operations.length()) {
            val op=operations.getJSONObject(i)
            val table=op.getString("table")
            if (!table.startsWith("health_")) continue
            touchesHealth=true
            if (op.getString("op")=="delete") continue
            val kind=when(table) {
                "health_measurements" -> "measurement"
                "health_samples" -> "sample"
                "health_journal_entries" -> "journal"
                else -> null
            }
            if (kind!=null) subjects+=kind to op.getJSONObject("key").getString("id")
        }
        if (!touchesHealth) return
        for ((kind,id) in subjects) {
            val present=db.query("SELECT 1 FROM health_ai_snapshots WHERE subject_kind=? AND subject_id=? LIMIT 1",arrayOf(kind,id)).use { it.moveToFirst() }
            require(present) { "Health patch lacks required AI snapshot for $kind" }
            if (kind == "measurement") {
                val invalid=db.query("SELECT 1 FROM health_measurements WHERE id=? AND ((numeric_value IS NULL AND text_value IS NULL AND interpretation_it IS NULL) OR availability_basis NOT IN ('source_timestamp','minsp_notification','chat_received_proxy','unknown'))",arrayOf(id)).use { it.moveToFirst() }
                require(!invalid) { "Health measurement lacks result or valid availability basis" }
            }
        }
        val invalidSubjects=db.query("""
            SELECT 1 FROM health_ai_snapshots a
            WHERE (a.subject_kind='measurement' AND NOT EXISTS(SELECT 1 FROM health_measurements WHERE id=a.subject_id))
               OR (a.subject_kind='sample' AND NOT EXISTS(SELECT 1 FROM health_samples WHERE id=a.subject_id))
               OR (a.subject_kind='journal' AND NOT EXISTS(SELECT 1 FROM health_journal_entries WHERE id=a.subject_id))
               OR a.subject_kind NOT IN ('measurement','sample','journal','health_state')
            LIMIT 1
        """.trimIndent()).use { it.moveToFirst() }
        require(!invalidSubjects) { "Health AI snapshot has no valid subject" }
        val future=db.query("""
            SELECT 1 FROM health_ai_evidence ev
            JOIN health_ai_snapshots a ON a.id=ev.snapshot_id
            WHERE (ev.evidence_kind='measurement' AND EXISTS(
                SELECT 1 FROM health_measurements m JOIN health_samples s ON s.id=m.sample_id JOIN health_events e ON e.id=s.event_id
                WHERE m.id=ev.evidence_id AND e.occurred_at_ms>a.as_of_ms))
               OR (ev.evidence_kind='sample' AND EXISTS(
                SELECT 1 FROM health_samples s JOIN health_events e ON e.id=s.event_id
                WHERE s.id=ev.evidence_id AND e.occurred_at_ms>a.as_of_ms))
               OR (ev.evidence_kind='journal' AND EXISTS(
                SELECT 1 FROM health_journal_entries j JOIN health_events e ON e.id=j.event_id
                WHERE j.id=ev.evidence_id AND e.occurred_at_ms>a.as_of_ms))
            LIMIT 1
        """.trimIndent()).use { it.moveToFirst() }
        require(!future) { "Health AI evidence is later than its clinical cutoff" }
        val missingEvidence=db.query("""
            SELECT 1 FROM health_ai_evidence ev WHERE
               (ev.evidence_kind='measurement' AND NOT EXISTS(SELECT 1 FROM health_measurements WHERE id=ev.evidence_id))
               OR (ev.evidence_kind='sample' AND NOT EXISTS(SELECT 1 FROM health_samples WHERE id=ev.evidence_id))
               OR (ev.evidence_kind='journal' AND NOT EXISTS(SELECT 1 FROM health_journal_entries WHERE id=ev.evidence_id))
            LIMIT 1
        """.trimIndent()).use { it.moveToFirst() }
        require(!missingEvidence) { "Health AI evidence has no valid subject" }
    }
}
