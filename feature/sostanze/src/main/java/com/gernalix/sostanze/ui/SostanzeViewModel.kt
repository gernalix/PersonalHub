package com.gernalix.sostanze.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gernalix.sostanze.capsules.importexport.ExportResult
import com.gernalix.sostanze.capsules.importexport.SostanzeImportExportCapsule
import com.gernalix.sostanze.data.IntakeUndoToken
import com.gernalix.sostanze.data.MacroEntity
import com.gernalix.sostanze.data.InteractionTargetKinds
import com.gernalix.sostanze.data.PrescriptionEntity
import com.gernalix.sostanze.data.SostanzeDatabase
import com.gernalix.sostanze.data.SostanzeRepository
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.SubstanceTypes
import com.gernalix.sostanze.data.UtcDateCodec
import com.gernalix.sostanze.data.toPlan
import com.gernalix.sostanze.data.toRecord
import com.gernalix.sostanze.domain.DoseButtonState
import com.gernalix.sostanze.domain.DoseSection
import com.gernalix.sostanze.domain.NotificationPlan
import com.gernalix.sostanze.domain.SostanzeEngine
import com.gernalix.sostanze.domain.StockCoverage
import com.gernalix.sostanze.notifications.SostanzeNotificationScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrescriptionUi(
    val prescription: PrescriptionEntity,
    val substanceName: String,
    val nextRefillDate: LocalDate,
)

data class StockUi(
    val substance: SubstanceEntity,
    val coverage: StockCoverage,
)

data class HistoryUi(
    val substanceName: String,
    val doseText: String,
    val timestampMs: Long,
    val ghost: Boolean = false,
)

data class MacroUi(
    val macro: MacroEntity,
    val items: List<SubstanceEntity>,
)

enum class ExportStatusUi {
    NotConfigured,
    Ready,
    Error,
}

data class SostanzeUiState(
    val substances: List<SubstanceEntity> = emptyList(),
    val doseStates: List<DoseButtonState> = emptyList(),
    val stockRows: List<StockUi> = emptyList(),
    val prescriptions: List<PrescriptionUi> = emptyList(),
    val history: List<HistoryUi> = emptyList(),
    val macros: List<MacroUi> = emptyList(),
    val notificationPlans: List<NotificationPlan> = emptyList(),
    val nowMs: Long = System.currentTimeMillis(),
    val lastExportResult: ExportResult = ExportResult.Skipped,
    val exportStatus: ExportStatusUi = ExportStatusUi.NotConfigured,
    val lastExportError: String? = null,
    val lastImportError: String? = null,
)

class SostanzeViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SostanzeDatabase.get(application)
    private val repository = SostanzeRepository(database)
    private val importExport = SostanzeImportExportCapsule(application, database)
    private val nowMs = MutableStateFlow(System.currentTimeMillis())
    private val lastExportResult = MutableStateFlow<ExportResult>(ExportResult.Skipped)
    private val lastImportError = MutableStateFlow<String?>(null)
    private val scheduledNotificationKeys = mutableSetOf<String>()

    val uiState = combine(repository.snapshot, nowMs, lastExportResult, lastImportError) { snapshot, now, exportResult, importError ->
        val substances = snapshot.substances
        val plans = substances.map { it.toPlan() }
        val intakes = snapshot.intakes.map { it.toRecord() }
        val rules = snapshot.interactionRules.map { it.toPlan(snapshot.interactionTargets) }
        val doseStates = plans
            .map { plan -> SostanzeEngine.doseState(plan, intakes, rules, plans, now) }
            .sortedWith(
                compareBy<DoseButtonState> { sectionOrder(it.section) }
                    .thenBy { it.nextIdealMs ?: Long.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.substance.name }
            )
        val stockRows = substances
            .filter { !it.archived }
            .map { entity -> StockUi(entity, SostanzeEngine.stockCoverage(entity.toPlan())) }
            .sortedBy { it.coverage.daysCovered ?: Double.MAX_VALUE }
        val nameById = substances.associateBy({ it.id }, { it.name })
        val substanceById = substances.associateBy { it.id }
        val prescriptions = snapshot.prescriptions.map { prescription ->
            PrescriptionUi(
                prescription = prescription,
                substanceName = nameById[prescription.substanceId].orEmpty(),
                nextRefillDate = SostanzeEngine.nextRefillDate(
                    prescription.prescriptionEpochDay,
                    prescription.refillEveryMonths
                ),
            )
        }
        val refillPlans = prescriptions
            .filter { it.prescription.alertRefill }
            .map {
                NotificationPlan(
                    kind = "refill",
                    entityId = it.prescription.substanceId,
                    scheduledForMs = it.nextRefillDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
            }
        val history = snapshot.intakes
            .sortedByDescending { it.timestampMs }
            .mapNotNull { intake ->
                val substance = substanceById[intake.substanceId] ?: return@mapNotNull null
                HistoryUi(
                    substanceName = substance.name,
                    doseText = "${intake.dose.clean()} ${intake.doseUnit}",
                    timestampMs = intake.timestampMs,
                )
            }
        val macros = snapshot.macros.map { macro ->
            MacroUi(
                macro = macro,
                items = snapshot.macroItems
                    .filter { it.macroId == macro.id }
                    .mapNotNull { substanceById[it.substanceId] }
            )
        }
        SostanzeUiState(
            substances = substances,
            doseStates = doseStates,
            stockRows = stockRows,
            prescriptions = prescriptions,
            history = history,
            macros = macros,
            notificationPlans = SostanzeEngine.interactionEndNotifications(doseStates) +
                SostanzeEngine.missedDoseNotifications(doseStates, now) +
                refillPlans,
            nowMs = now,
            lastExportResult = exportResult,
            exportStatus = exportResult.toUiStatus(),
            lastExportError = (exportResult as? ExportResult.Failure)?.message,
            lastImportError = importError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SostanzeUiState())

    init {
        SostanzeNotificationScheduler.ensureChannel(application)
        viewModelScope.launch {
            repository.seedIfEmpty()
            refreshExport()
        }
        viewModelScope.launch { lastExportResult.value = importExport.exportStatusNow() }
        viewModelScope.launch {
            while (true) {
                nowMs.value = System.currentTimeMillis()
                delay(60_000)
            }
        }
        viewModelScope.launch {
            uiState.collect { state ->
                val names = state.substances.associateBy({ it.id }, { it.name })
                val newPlans = state.notificationPlans.filter { plan ->
                    scheduledNotificationKeys.add("${plan.kind}:${plan.entityId}:${plan.scheduledForMs}")
                }
                repository.saveNotifications(newPlans)
                if (newPlans.isNotEmpty()) refreshExport()
                newPlans.forEach { plan ->
                    SostanzeNotificationScheduler.schedule(getApplication(), plan, names[plan.entityId].orEmpty())
                }
            }
        }
    }

    fun recordIntake(substanceId: Long, onRecorded: (IntakeUndoToken) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.recordIntake(substanceId)
            refreshExport()
            if (id > 0) onRecorded(IntakeUndoToken(listOf(id)))
        }
    }

    fun recordMacro(macroId: Long, onRecorded: (IntakeUndoToken) -> Unit = {}) {
        viewModelScope.launch {
            val ids = repository.recordMacro(macroId)
            refreshExport()
            if (ids.isNotEmpty()) onRecorded(IntakeUndoToken(ids))
        }
    }

    fun undoLastIntake(substanceId: Long) {
        viewModelScope.launch {
            repository.undoLastIntake(substanceId)
            refreshExport()
        }
    }

    fun undoToken(token: IntakeUndoToken) {
        viewModelScope.launch {
            repository.undoIntakes(token.intakeIds)
            refreshExport()
        }
    }

    fun adjustStock(substanceId: Long, delta: Double, note: String?) {
        viewModelScope.launch {
            repository.adjustStock(substanceId, delta, note)
            refreshExport()
        }
    }

    fun saveSubstance(entity: SubstanceEntity) {
        viewModelScope.launch {
            repository.saveSubstance(entity)
            refreshExport()
        }
    }

    fun archiveSubstance(id: Long) {
        viewModelScope.launch {
            repository.archiveSubstance(id)
            refreshExport()
        }
    }

    fun addPrescription(substanceId: Long, quantity: Double, refillMonths: Int, alert: Boolean) {
        viewModelScope.launch {
            repository.savePrescription(
                PrescriptionEntity(
                    substanceId = substanceId,
                    prescriptionEpochDay = LocalDate.now().toEpochDay(),
                    prescriptionDateUtc = UtcDateCodec.prescriptionIsoUtc(LocalDate.now().toEpochDay()),
                    quantityPrescribed = quantity,
                    refillEveryMonths = refillMonths,
                    alertRefill = alert,
                )
            )
            refreshExport()
        }
    }

    fun addAllFutureInteraction(sourceSubstanceId: Long, beforeHours: Double, afterHours: Double) {
        viewModelScope.launch {
            repository.saveInteractionRule(
                rule = com.gernalix.sostanze.data.InteractionRuleEntity(
                    sourceSubstanceId = sourceSubstanceId,
                    avoidBeforeHours = beforeHours,
                    avoidAfterHours = afterHours,
                ),
                targetKind = InteractionTargetKinds.ALL_PRESENT_AND_FUTURE,
                targetSubstanceId = null,
            )
            refreshExport()
        }
    }

    fun defaultNewSubstance(): SubstanceEntity =
        SubstanceEntity(
            name = "",
            type = SubstanceTypes.FARMACO,
            stockCurrent = 0.0,
            stockUnit = "mg",
            dosePerIntake = 1.0,
            doseUnit = "mg",
            dailyFrequency = 1,
            startEpochDay = LocalDate.now().toEpochDay(),
            forever = true,
        )

    fun historyFor(substanceId: Long, state: SostanzeUiState = uiState.value): List<HistoryUi> {
        val substance = state.substances.firstOrNull { it.id == substanceId } ?: return emptyList()
        val realRows = state.history.filter { it.substanceName == substance.name }
        val ghosts = missedGhostRows(substance, state)
        return (realRows + ghosts).sortedByDescending { it.timestampMs }
    }

    fun setExportFolder(uri: Uri) {
        viewModelScope.launch {
            importExport.setExportDestination(uri)
            lastExportResult.value = importExport.exportStatusNow()
        }
    }

    fun importDatabase(uri: Uri) {
        viewModelScope.launch {
            val result = importExport.importFrom(uri)
            lastImportError.value = result.exceptionOrNull()?.message
            lastExportResult.value = importExport.exportStatusNow()
        }
    }

    private suspend fun refreshExport() {
        lastExportResult.value = importExport.exportStatusNow()
    }

    private fun sectionOrder(section: DoseSection): Int = when (section) {
        DoseSection.DUE_TODAY -> 0
        DoseSection.LATER -> 1
        DoseSection.BLOCKED -> 2
        DoseSection.TAKEN -> 3
        DoseSection.PRN -> 4
        DoseSection.ARCHIVED -> 5
    }

    private fun ExportResult.toUiStatus(): ExportStatusUi = when (this) {
        ExportResult.Skipped -> ExportStatusUi.NotConfigured
        ExportResult.Success -> ExportStatusUi.Ready
        is ExportResult.Failure -> ExportStatusUi.Error
    }

    private fun missedGhostRows(substance: SubstanceEntity, state: SostanzeUiState): List<HistoryUi> {
        if (substance.prn || substance.dailyFrequency <= 0) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(state.nowMs).atZone(zone).toLocalDate()
        val end = substance.endEpochDay?.let(LocalDate::ofEpochDay) ?: today
        val start = LocalDate.ofEpochDay(substance.startEpochDay)
        val real = state.history.filter { it.substanceName == substance.name }
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .flatMap { date ->
                val count = real.count { row ->
                    Instant.ofEpochMilli(row.timestampMs).atZone(zone).toLocalDate() == date
                }
                val missing = (substance.dailyFrequency - count).coerceAtLeast(0)
                List(missing) {
                    HistoryUi(
                        substanceName = substance.name,
                        doseText = "${substance.dosePerIntake.clean()} ${substance.doseUnit}",
                        timestampMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                        ghost = true,
                    )
                }.asSequence()
            }
            .toList()
    }
}

private fun Double.clean(): String =
    if (this % 1.0 == 0.0) toLong().toString() else "%.2f".format(java.util.Locale.US, this).trimEnd('0').trimEnd('.')
