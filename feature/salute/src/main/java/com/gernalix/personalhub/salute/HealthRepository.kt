package com.gernalix.personalhub.salute

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.gernalix.personalhub.core.database.capsules.gitdata.GitReadOnlyArtifactClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class HealthTimelineItem(
    val data: String?,
    val dataMs: Long,
    val tipo: String,
    val titolo: String,
    val sottotitolo: String?,
    val valore: String?,
    val flag: String?,
    val tempoReferto: String?,
    val dettaglio: String?,
    val commentoAi: String?,
    val sourceKind: String,
    val sourceId: Long,
)

data class HealthMeasurementHistoryItem(
    val sourceId: Long,
    val data: String?,
    val dataMs: Long,
    val esame: String,
    val numericValue: Double?,
    val risultato: String?,
    val unita: String?,
    val flag: String?,
    val tempoReferto: String?,
)

data class HealthJournalDetail(
    val sourceId: Long,
    val data: String?,
    val dataMs: Long?,
    val tipo: String?,
    val reparto: String?,
    val struttura: String?,
    val clinico: String?,
    val ruolo: String?,
    val titolo: String?,
    val nota: String,
    val commentoAi: String?,
    val incertezzeAi: String?,
    val originaleDa: String,
)

data class HealthEvidence(
    val kind: String,
    val id: Long,
    val label: String?,
    val relevance: String,
)

data class HealthTurnaroundSummary(
    val count: Int,
    val averageMs: Long,
) {
    val formatted: String
        get() {
            val days = averageMs / 86_400_000L
            val hours = (averageMs % 86_400_000L) / 3_600_000L
            return "${days}g ${hours}h"
        }
}

data class HealthSyncState(
    val installedRevision: String?,
    val lastSyncMs: Long,
    val warning: String?,
)

data class HealthSnapshot(
    val timeline: List<HealthTimelineItem>,
    val turnaround: HealthTurnaroundSummary?,
    val sync: HealthSyncState,
)

sealed interface HealthLoadResult {
    data class Ready(val snapshot: HealthSnapshot) : HealthLoadResult
    data class Error(val message: String) : HealthLoadResult
}

class HealthRepository(context: Context) {
    // External read-only cache; never part of personalhub.db.
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheDir = File(app.noBackupFilesDir, "salute")
    private val active = File(cacheDir, DB_NAME)
    private val backup = File(cacheDir, "$DB_NAME.bak")
    private val staging = File(cacheDir, "$DB_NAME.staging")

    suspend fun load(forceRefresh: Boolean = false): HealthLoadResult = withContext(Dispatchers.IO) {
        val warning = runCatching { refreshIfNeeded(forceRefresh) }.exceptionOrNull()?.message
        if (!active.isFile) {
            return@withContext HealthLoadResult.Error(
                warning ?: "Dati Salute non ancora scaricati",
            )
        }
        runCatching {
            val timeline = timeline()
            val turnaround = turnaroundSummary()
            HealthLoadResult.Ready(
                HealthSnapshot(
                    timeline = timeline,
                    turnaround = turnaround,
                    sync = HealthSyncState(
                        installedRevision = prefs.getString(KEY_REVISION, null),
                        lastSyncMs = prefs.getLong(KEY_SYNC_MS, 0L),
                        warning = warning,
                    ),
                ),
            )
        }.getOrElse { error ->
            HealthLoadResult.Error(error.message ?: "Impossibile leggere salute.db")
        }
    }

    suspend fun measurementHistory(sourceId: Long): List<HealthMeasurementHistoryItem> =
        withContext(Dispatchers.IO) {
            withDatabase { db ->
                val sql = """
                    SELECT source_id, data, data_ms, esame, numeric_value,
                           risultato, unita, flag, tempo_referto
                    FROM v_health_measurement_history
                    WHERE esame = (
                        SELECT esame
                        FROM v_health_measurement_history
                        WHERE source_id = ?
                        LIMIT 1
                    )
                    ORDER BY data_ms DESC
                """.trimIndent()
                db.rawQuery(sql, arrayOf(sourceId.toString())).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                HealthMeasurementHistoryItem(
                                    sourceId = cursor.getLong(0),
                                    data = cursor.stringOrNull(1),
                                    dataMs = cursor.getLong(2),
                                    esame = cursor.getString(3),
                                    numericValue = if (cursor.isNull(4)) null else cursor.getDouble(4),
                                    risultato = cursor.stringOrNull(5),
                                    unita = cursor.stringOrNull(6),
                                    flag = cursor.stringOrNull(7),
                                    tempoReferto = cursor.stringOrNull(8),
                                ),
                            )
                        }
                    }
                }
            }
        }

    suspend fun journalDetail(sourceId: Long): HealthJournalDetail? =
        withContext(Dispatchers.IO) {
            withDatabase { db ->
                db.rawQuery(
                    """
                    SELECT source_id, data, data_ms, tipo, reparto, struttura,
                           clinico, ruolo, titolo, nota, commento_ai,
                           incertezze_ai, originale_da
                    FROM v_health_journal_detail
                    WHERE source_id = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(sourceId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@withDatabase null
                    HealthJournalDetail(
                        sourceId = cursor.getLong(0),
                        data = cursor.stringOrNull(1),
                        dataMs = if (cursor.isNull(2)) null else cursor.getLong(2),
                        tipo = cursor.stringOrNull(3),
                        reparto = cursor.stringOrNull(4),
                        struttura = cursor.stringOrNull(5),
                        clinico = cursor.stringOrNull(6),
                        ruolo = cursor.stringOrNull(7),
                        titolo = cursor.stringOrNull(8),
                        nota = cursor.getString(9),
                        commentoAi = cursor.stringOrNull(10),
                        incertezzeAi = cursor.stringOrNull(11),
                        originaleDa = cursor.getString(12),
                    )
                }
            }
        }

    suspend fun journalEvidence(sourceId: Long): List<HealthEvidence> =
        withContext(Dispatchers.IO) {
            withDatabase { db ->
                db.rawQuery(
                    """
                    SELECT
                        e.evidence_kind,
                        e.evidence_id,
                        CASE
                            WHEN e.evidence_kind = 'measurement' THEN (
                                SELECT h.esame
                                FROM v_health_measurement_history AS h
                                WHERE h.source_id = e.evidence_id
                                LIMIT 1
                            )
                            WHEN e.evidence_kind = 'journal' THEN (
                                SELECT j.titolo
                                FROM v_health_journal_detail AS j
                                WHERE j.source_id = e.evidence_id
                                LIMIT 1
                            )
                            ELSE NULL
                        END AS label,
                        e.rilevanza
                    FROM v_health_snapshot_evidence AS e
                    WHERE e.journal_source_id = ?
                    ORDER BY e.evidence_kind, e.evidence_id
                    """.trimIndent(),
                    arrayOf(sourceId.toString()),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                HealthEvidence(
                                    kind = cursor.getString(0),
                                    id = cursor.getLong(1),
                                    label = cursor.stringOrNull(2),
                                    relevance = cursor.getString(3),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun refreshIfNeeded(force: Boolean) {
        cacheDir.mkdirs()
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_CHECK_MS, 0L)
        if (!force && active.isFile && now - lastCheck < STALE_AFTER_MS) return

        prefs.edit().putLong(KEY_CHECK_MS, now).apply()

        val head = GitReadOnlyArtifactClient.remoteHead(app, REPOSITORY_URL)
        val installed = prefs.getString(KEY_REVISION, null)
        if (active.isFile && installed == head.revision) {
            prefs.edit().remove(KEY_ERROR).apply()
            return
        }

        val artifact = GitReadOnlyArtifactClient.fetch(
            context = app,
            repositoryUrl = REPOSITORY_URL,
            path = DB_NAME,
            revision = head.revision,
        )
        require(artifact.revision == head.revision) { "Il repository Salute è cambiato durante il download" }
        require(artifact.bytes.size in SQLITE_HEADER.size..MAX_BYTES) { "salute.db ha una dimensione non valida" }
        require(artifact.bytes.copyOfRange(0, SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)) {
            "Il file remoto non è un database SQLite"
        }

        if (staging.exists()) staging.delete()
        FileOutputStream(staging).use { output ->
            output.write(artifact.bytes)
            output.fd.sync()
        }

        validate(staging)

        if (backup.exists()) backup.delete()
        if (active.exists()) {
            require(active.renameTo(backup)) { "Impossibile preservare la copia Salute precedente" }
        }
        if (!staging.renameTo(active)) {
            if (backup.exists()) backup.renameTo(active)
            error("Impossibile installare il nuovo database Salute")
        }

        try {
            validate(active)
        } catch (error: Throwable) {
            active.delete()
            if (backup.exists()) backup.renameTo(active)
            throw error
        }

        backup.delete()
        prefs.edit()
            .putString(KEY_REVISION, head.revision)
            .putLong(KEY_SYNC_MS, now)
            .remove(KEY_ERROR)
            .apply()
    }

    private fun validate(file: File) {
        require(file.isFile && file.length() in SQLITE_HEADER.size.toLong()..MAX_BYTES.toLong()) {
            "Database Salute non valido"
        }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Controllo integrità Salute fallito"
                }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                require(!cursor.moveToFirst()) { "Foreign key Salute non valide" }
            }
            val contract = db.rawQuery(
                "SELECT value FROM metadata WHERE key = 'consumer_contract_version' LIMIT 1",
                null,
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Contratto Salute mancante" }
                cursor.getString(0).toInt()
            }
            require(contract == SUPPORTED_CONTRACT) {
                if (contract > SUPPORTED_CONTRACT) {
                    "Aggiorna PersonalHub per leggere questa versione di Salute"
                } else {
                    "Versione Salute troppo vecchia"
                }
            }
            REQUIRED_COLUMNS.forEach { (view, expected) ->
                val actual = db.rawQuery("SELECT * FROM $view LIMIT 0", null).use {
                    it.columnNames.toSet()
                }
                require(expected.all(actual::contains)) { "Vista Salute incompatibile: $view" }
            }
        } finally {
            db.close()
        }
    }

    private fun timeline(): List<HealthTimelineItem> =
        withDatabase { db ->
            db.rawQuery(
                """
                SELECT data, data_ms, tipo, titolo, sottotitolo, valore, flag,
                       tempo_referto, dettaglio, commento_ai, source_kind, source_id
                FROM v_health_timeline
                ORDER BY data_ms DESC, tipo, titolo
                """.trimIndent(),
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            HealthTimelineItem(
                                data = cursor.stringOrNull(0),
                                dataMs = cursor.getLong(1),
                                tipo = cursor.getString(2),
                                titolo = cursor.getString(3),
                                sottotitolo = cursor.stringOrNull(4),
                                valore = cursor.stringOrNull(5),
                                flag = cursor.stringOrNull(6),
                                tempoReferto = cursor.stringOrNull(7),
                                dettaglio = cursor.stringOrNull(8),
                                commentoAi = cursor.stringOrNull(9),
                                sourceKind = cursor.getString(10),
                                sourceId = cursor.getLong(11),
                            ),
                        )
                    }
                }
            }
        }

    private fun turnaroundSummary(): HealthTurnaroundSummary? =
        withDatabase { db ->
            db.rawQuery(
                "SELECT COUNT(*), AVG(delta_ms) FROM v_test_turnaround WHERE delta_ms IS NOT NULL",
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) == 0 || cursor.isNull(1)) {
                    null
                } else {
                    HealthTurnaroundSummary(
                        count = cursor.getInt(0),
                        averageMs = cursor.getDouble(1).toLong(),
                    )
                }
            }
        }

    private fun <T> withDatabase(block: (SQLiteDatabase) -> T): T {
        require(active.isFile) { "Dati Salute non ancora scaricati" }
        val db = SQLiteDatabase.openDatabase(active.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    companion object {
        private const val REPOSITORY_URL = "https://github.com/gernalix/salute"
        private const val DB_NAME = "salute.db"
        private const val PREFS = "salute_read_only_cache"
        private const val KEY_REVISION = "installed_revision"
        private const val KEY_SYNC_MS = "last_sync_ms"
        private const val KEY_CHECK_MS = "last_check_ms"
        private const val KEY_ERROR = "last_error"
        private const val STALE_AFTER_MS = 15 * 60 * 1000L
        private const val MAX_BYTES = 25 * 1024 * 1024
        private const val SUPPORTED_CONTRACT = 2
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val REQUIRED_COLUMNS = mapOf(
            "v_health_timeline" to setOf(
                "data", "data_ms", "tipo", "titolo", "sottotitolo", "valore", "flag",
                "tempo_referto", "dettaglio", "commento_ai", "source_kind", "source_id",
            ),
            "v_all_health_data" to setOf(
                "data", "data_ms", "categoria", "esame", "risultato", "unita", "flag",
                "campione", "tempo_referto",
            ),
            "v_test_turnaround" to setOf(
                "data_prelievo", "data_ms", "categoria", "esame", "ricevuto_ms",
                "delta_ms", "delta",
            ),
            "v_health_measurement_history" to setOf(
                "source_id", "data", "data_ms", "esame", "numeric_value", "risultato",
                "unita", "flag", "tempo_referto",
            ),
            "v_health_journal_detail" to setOf(
                "source_id", "data", "data_ms", "tipo", "reparto", "struttura", "clinico",
                "ruolo", "titolo", "nota", "commento_ai", "incertezze_ai", "originale_da",
            ),
            "v_health_snapshot_evidence" to setOf(
                "journal_source_id", "evidence_kind", "evidence_id", "rilevanza",
            ),
        )
    }
}
