package com.gernalix.sostanze.capsules

import android.net.Uri

data class SubstanceRef(val id: Long, val name: String)
data class DoseRef(val value: Double, val unit: String = "mg")
data class IntakeUndoRef(val intakeIds: List<Long>)
data class HistoryRowRef(val substanceName: String, val dose: DoseRef, val timestampMs: Long, val ghost: Boolean)
data class MacroRef(val id: Long, val name: String, val items: List<SubstanceRef>)
data class PrescriptionRef(val id: Long, val substance: SubstanceRef, val dose: DoseRef, val prescriptionEpochDay: Long)
data class InteractionBlockRef(val substance: SubstanceRef, val source: SubstanceRef, val untilMs: Long)

sealed class CapsuleResult {
    data object Ok : CapsuleResult()
    data object Skipped : CapsuleResult()
    data class Error(val message: String) : CapsuleResult()
}

interface SostanzeCapsuleApi {
    suspend fun archiveSubstance(id: Long)
    suspend fun publicLabel(id: Long): SubstanceRef?
}

interface AssunzioniCapsuleApi {
    suspend fun record(substanceId: Long): IntakeUndoRef
    suspend fun recordBatch(substanceIds: List<Long>): IntakeUndoRef
    suspend fun undo(token: IntakeUndoRef): Boolean
}

interface MacroCapsuleApi {
    suspend fun execute(macroId: Long): IntakeUndoRef
}

interface HistoryCapsuleApi {
    fun globalHistory(): List<HistoryRowRef>
    fun substanceHistory(substanceId: Long): List<HistoryRowRef>
}

interface ImportExportCapsuleApi {
    suspend fun setExportDestination(treeUri: Uri): CapsuleResult
    suspend fun exportNow(): CapsuleResult
    suspend fun importDatabase(sourceUri: Uri): CapsuleResult
}

interface NotificationsCapsuleApi {
    fun ensureReady()
    fun schedule(kind: String, entityId: Long, scheduledForMs: Long, label: String)
}
