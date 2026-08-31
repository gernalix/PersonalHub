// v471
// v435
package com.example.multitimetracker.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.multitimetracker.AppPatchVersion
import com.example.multitimetracker.R
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.export.VaultFolders
import org.json.JSONObject

/**
 * v423 - Data Integrity Gate (FAIL-FAST).
 *
 * Goal (user-facing): zero silent data loss.
 * If the DB looks inconsistent, we STOP the app and force a recovery choice.
 */
object DataIntegrityGate {

    private const val REPORT_FILE = "integrity_report.txt"

    data class GateResult(
        val ok: Boolean,
        val blockingTitle: String? = null,
        val blockingBody: String? = null,
        val technicalReport: String? = null,
        val stats: IntegrityStatsSqlite.Stats? = null
    )

    /**
     * Runs critical checks on the INTERNAL DB.
     *
     * IMPORTANT: this must be cheap and deterministic.
     */
    fun runCriticalChecks(context: Context): GateResult {
        return runCatching {
            val stats = IntegrityStatsSqlite.computeInternal(context)
            val issues = mutableListOf<String>()

            // 1) Required tables must exist (very defensive).
            val db = SnapshotSqlite.openReadableDb(context)
            try {
                issues += validateRequiredTables(db)
                issues += validateSessionTagOrphans(db)
                issues += validateSnapshotTagOrphans(context, db)
            } finally {
                db.close()
            }
            val criticalCounts = CriticalDataGuard.readInternal(context)
            CriticalDataGuard.lastGood(context)?.let { previous ->
                CriticalDataGuard.criticalDrops(
                    before = previous,
                    after = criticalCounts,
                    includeLegacyTasks = false,
                ).forEach { drop ->
                    issues += "critical persistent data count dropped silently: $drop"
                }
            }

            // 2) Catastrophic empty-state detection.
            // If there are *some* sessions historically, totalClosedSessionMs must not suddenly be 0.
            if (stats.sessions >= 25 && stats.totalClosedSessionMs <= 0L) {
                issues += "totalClosedSessionMs is 0 while sessions=${stats.sessions} (catastrophic loss suspicion)"
            }

            if (issues.isEmpty()) {
                CriticalDataGuard.rememberLastGood(context, criticalCounts)
                GateResult(ok = true, stats = stats)
            } else {
                val report = buildString {
                    appendLine("Integrity gate FAILED")
                    appendLine("patch=${AppPatchVersion.current(context)}")
                    appendLine("sessions=${stats.sessions}, sessionTags=${stats.sessionTags}, tags(snapshot)=${stats.tags}")
                    appendLine()
                    issues.forEach { appendLine("- $it") }
                }.trim()

                // v435: best-effort external report for recovery/debug (SAF/logs/integrity_report.txt)
                writeSafReportBestEffort(context, report)

                GateResult(
                    ok = false,
                    blockingTitle = context.getString(R.string.integrity_gate_title),
                    blockingBody = context.getString(R.string.integrity_gate_body),
                    technicalReport = report,
                    stats = stats
                )
            }
        }.getOrElse {
            writeSafReportBestEffort(
                context,
                "Integrity gate crashed: ${it::class.java.simpleName}: ${it.message}"
            )
            GateResult(
                ok = false,
                blockingTitle = context.getString(R.string.integrity_gate_title),
                blockingBody = context.getString(R.string.integrity_gate_body),
                technicalReport = "Integrity gate crashed: ${it::class.java.simpleName}: ${it.message}"
            )
        }
    }

    private fun writeSafReportBestEffort(context: Context, report: String) {
        // Only if SAF is configured. Never throw from here.
        if (BackupFolderStore.getTreeUri(context) == null) return
        runCatching {
            val root = VaultFolders.ensureRoot(context)
            val logs = root.logs
            val doc = logs.findFile(REPORT_FILE)?.takeIf { it.isFile }
                ?: logs.createFile("text/plain", REPORT_FILE)
                ?: return
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                out.write(report.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    /**
     * v423: catastrophic loss detector used during imports/switches.
     */
    fun isCatastrophicLoss(before: IntegrityStatsSqlite.Stats, after: IntegrityStatsSqlite.Stats): Boolean {
        fun droppedTooMuch(beforeVal: Long, afterVal: Long): Boolean {
            if (beforeVal < 50L) return false // too small to reason
            return afterVal < (beforeVal / 10L) // <10%
        }

        return droppedTooMuch(before.sessions, after.sessions) ||
            droppedTooMuch(before.sessionTags, after.sessionTags) ||
            droppedTooMuch(before.tags, after.tags) ||
            droppedTooMuch(before.closedSessions, after.closedSessions) ||
            droppedTooMuch(before.tagSessions, after.tagSessions) ||
            (before.totalClosedSessionMs >= 60_000L && after.totalClosedSessionMs < (before.totalClosedSessionMs / 10L))
    }

    private fun validateRequiredTables(db: SQLiteDatabase): List<String> {
        fun hasTable(name: String): Boolean {
            return db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(name)
            ).use { it.moveToFirst() }
        }

        val missing = mutableListOf<String>()
        if (!hasTable("snapshot")) missing += "snapshot table missing"
        if (!hasTable(SnapshotSqlite.SESSIONS_TABLE)) missing += "${SnapshotSqlite.SESSIONS_TABLE} table missing"
        if (!hasTable(SnapshotSqlite.SESSION_TAGS_TABLE)) missing += "${SnapshotSqlite.SESSION_TAGS_TABLE} table missing"
        if (!hasTable(SnapshotSqlite.AUDIT_TABLE)) missing += "${SnapshotSqlite.AUDIT_TABLE} table missing"
        return missing
    }

    private fun validateSessionTagOrphans(db: SQLiteDatabase): List<String> {
        val issues = mutableListOf<String>()

        val orphanSessions = db.rawQuery(
            """
            SELECT COUNT(*)
            FROM ${SnapshotSqlite.SESSION_TAGS_TABLE} st
            LEFT JOIN ${SnapshotSqlite.SESSIONS_TABLE} s ON s.id = st.session_id
            WHERE s.id IS NULL
            """.trimIndent(),
            null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        if (orphanSessions > 0L) {
            issues += "session_tags contains $orphanSessions edges pointing to missing sessions"
        }

        return issues
    }

    private fun validateSnapshotTagOrphans(context: Context, db: SQLiteDatabase): List<String> {
        val issues = mutableListOf<String>()
        val snapshotJson = SnapshotSqlite.readSnapshot(context) ?: return issues

        val root = JSONObject(snapshotJson)
        val arr = root.optJSONArray("tags") ?: return issues
        val tagIds = HashSet<Long>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optLong("id", -1L)
            if (id > 0L) tagIds.add(id)
        }

        if (tagIds.isEmpty()) return issues

        // Count session_tags.tag_id values that are not present in snapshot.tags[].id.
        // NOTE: we build a temporary IN clause only if it's reasonably sized.
        if (tagIds.size <= 900) {
            val placeholders = tagIds.joinToString(",") { "?" }
            val args = tagIds.map { it.toString() }.toTypedArray()
            val bad = db.rawQuery(
                "SELECT COUNT(*) FROM ${SnapshotSqlite.SESSION_TAGS_TABLE} WHERE tag_id NOT IN ($placeholders)",
                args
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

            if (bad > 0L) {
                issues += "session_tags contains $bad edges pointing to tag_ids missing from snapshot.tags"
            }
        }

        return issues
    }
}
