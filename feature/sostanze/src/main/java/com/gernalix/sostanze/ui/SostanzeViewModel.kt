package com.gernalix.sostanze.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gernalix.sostanze.capsules.importexport.ExportResult
import com.gernalix.sostanze.capsules.importexport.SostanzeImportExportCapsule
import com.gernalix.sostanze.data.IntakeUndoToken
import com.gernalix.sostanze.data.IntakeOutcome
import com.gernalix.sostanze.data.IntakeEditOutcome
import com.gernalix.sostanze.data.MacroEntity
import com.gernalix.sostanze.data.InteractionTargetKinds
import com.gernalix.sostanze.data.InteractionRuleEntity
import com.gernalix.sostanze.data.InteractionTargetEntity
import com.gernalix.sostanze.data.DoctorChoice
import com.gernalix.sostanze.data.CostChoice
import com.gernalix.sostanze.data.PrescriptionEntity
import com.gernalix.sostanze.data.PrescriptionDraft
import com.gernalix.sostanze.data.SostanzeDatabase
import com.gernalix.sostanze.data.SostanzeRepository
import com.gernalix.sostanze.data.StockOutcome
import com.gernalix.sostanze.data.SubstanceSaveOutcome
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
import com.gernalix.sostanze.notifications.SostanzeRandomAlertWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrescriptionUi(
    val prescription: PrescriptionEntity,
    val substanceName: String,
    val nextRefillDate: LocalDate,
    val doctorName: String? = null,
    val costAmount: String? = null,
)

data class StockUi(
    val substance: SubstanceEntity,
    val coverage: StockCoverage,
)

data class HistoryUi(
    val id: Long,
    val substanceId: Long,
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
    val interactionRules: List<InteractionRuleEntity> = emptyList(),
    val interactionTargets: List<InteractionTargetEntity> = emptyList(),
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
    private val secondaryStateEnabled = MutableStateFlow(false)
    private val scheduledNotificationKeys = mutableSetOf<String>()
    private var maintenanceStarted = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val snapshot = secondaryStateEnabled.flatMapLatest { enabled ->
        if (enabled) repository.snapshot else repository.homeSnapshot
    }

    val uiState = combine(snapshot, nowMs, lastExportResult, lastImportError) { snapshot, now, exportResult, importError ->
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
        val prescriptions = snapshot.prescriptionDetails.map { detail ->
            val prescription = detail.prescription
            PrescriptionUi(
                prescription = prescription,
                substanceName = nameById[prescription.substanceId].orEmpty(),
                nextRefillDate = SostanzeEngine.nextRefillDate(
                    prescription.prescriptionEpochDay,
                    prescription.refillEveryMonths
                ),
                doctorName = detail.doctorName,
                costAmount = detail.costAmount,
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
                    id = intake.id,
                    substanceId = intake.substanceId,
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
            interactionRules = snapshot.interactionRules,
            interactionTargets = snapshot.interactionTargets,
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
        viewModelScope.launch {
            repository.initialize()
        }
        viewModelScope.launch { tickClock() }
    }

    fun loadSecondaryState() {
        if (maintenanceStarted) return
        maintenanceStarted = true
        secondaryStateEnabled.value = true
        SostanzeNotificationScheduler.ensureChannel(getApplication())
        viewModelScope.launch { lastExportResult.value = importExport.exportStatusNow() }
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

    private suspend fun tickClock() {
        while (true) {
            nowMs.value = System.currentTimeMillis()
            delay(60_000)
        }
    }

    fun recordIntake(substanceId: Long, onResult: (IntakeOutcome, IntakeUndoToken?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val outcome = repository.recordIntake(substanceId)
            if (outcome is IntakeOutcome.Recorded) refreshExport()
            onResult(outcome, (outcome as? IntakeOutcome.Recorded)?.let { IntakeUndoToken(listOf(it.id)) })
        }
    }

    fun recordMacro(macroId: Long, onRecorded: (List<IntakeOutcome>, IntakeUndoToken?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val outcomes = repository.recordMacro(macroId)
            val ids = outcomes.mapNotNull { (it as? IntakeOutcome.Recorded)?.id }
            if (ids.isNotEmpty()) refreshExport()
            onRecorded(outcomes, ids.takeIf { it.isNotEmpty() }?.let(::IntakeUndoToken))
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

    fun editIntake(id: Long, timestampMs: Long, quantity: Double, onResult: (IntakeEditOutcome) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.editIntake(id, timestampMs, quantity)
            if (result == IntakeEditOutcome.Updated) refreshExport()
            onResult(result)
        }
    }

    fun deleteIntake(id: Long) {
        viewModelScope.launch { if (repository.deleteIntake(id)) refreshExport() }
    }

    fun savePrescription(value: PrescriptionEntity, onSaved: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val saved = runCatching { repository.savePrescription(value) }.isSuccess
            if (saved) refreshExport()
            onSaved(saved)
        }
    }

    fun createPrescription(value: PrescriptionDraft, onSaved: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val saved = runCatching { repository.createPrescription(value) }.isSuccess
            if (saved) refreshExport()
            onSaved(saved)
        }
    }

    fun prescriptionPrefill(name: String, onResult: (PrescriptionEntity?) -> Unit) {
        viewModelScope.launch { onResult(repository.prescriptionPrefill(name)) }
    }

    fun doctorChoices(query: String, onResult: (List<DoctorChoice>) -> Unit) {
        viewModelScope.launch { onResult(repository.doctorChoices(query)) }
    }

    fun costChoices(name: String, onResult: (List<CostChoice>) -> Unit) {
        viewModelScope.launch { onResult(repository.recentMatchingCosts(name)) }
    }

    fun deletePrescription(id: Long) {
        viewModelScope.launch { repository.deletePrescription(id); refreshExport() }
    }

    fun adjustStock(substanceId: Long, delta: Double, note: String?, onResult: (StockOutcome) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.adjustStock(substanceId, delta, note)
            if (result is StockOutcome.Applied) refreshExport()
            onResult(result)
        }
    }

    fun setStock(substanceId: Long, target: Double, onResult: (StockOutcome) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.setStock(substanceId, target)
            if (result is StockOutcome.Applied) refreshExport()
            onResult(result)
        }
    }

    fun saveSubstance(entity: SubstanceEntity, onResult: (SubstanceSaveOutcome) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.saveSubstance(entity)
            if (result is SubstanceSaveOutcome.Saved) refreshExport()
            onResult(result)
        }
    }

    fun saveRandomAlerts(substance: SubstanceEntity, enabled: Boolean, count: Int, window: SostanzeRandomAlertWindow) {
        SostanzeNotificationScheduler.saveRandomAlertConfig(
            context = getApplication(),
            substanceId = substance.id,
            label = substance.name,
            enabled = enabled,
            count = count,
            window = window,
        )
    }

    fun archiveSubstance(id: Long) {
        viewModelScope.launch {
            repository.archiveSubstance(id)
            refreshExport()
        }
    }

    fun restoreSubstance(id: Long) {
        viewModelScope.launch { repository.restoreSubstance(id); refreshExport() }
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
                    orderEpochDay = LocalDate.now().toEpochDay(),
                    packageDoseCount = quantity.toInt().coerceAtLeast(1),
                    remainingDoses = quantity.toInt().coerceAtLeast(1),
                    doseMg = uiState.value.substances.firstOrNull { it.id == substanceId }?.dosePerIntake ?: 1.0,
                )
            )
            refreshExport()
        }
    }

    fun saveAllFutureInteraction(ruleId: Long = 0, sourceSubstanceId: Long, beforeHours: Double, afterHours: Double) {
        viewModelScope.launch {
            repository.saveInteractionRule(
                rule = com.gernalix.sostanze.data.InteractionRuleEntity(
                    id = ruleId,
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

    fun deleteInteraction(ruleId: Long) {
        viewModelScope.launch { repository.deleteInteractionRule(ruleId); refreshExport() }
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
        return state.history.filter { it.substanceId == substanceId }.sortedByDescending { it.timestampMs }
    }

    fun setExportFolder(uri: Uri) {
        viewModelScope.launch {
            importExport.setExportDestination(uri)
            lastExportResult.value = importExport.exportStatusNow()
        }
    }

    fun importDatabase(uri: Uri) {
        viewModelScope.launch {
            runCatching { importExport.importDatabase(uri) }
                .onFailure { lastImportError.value = it.message }
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

}

private fun Double.clean(): String =
    if (this % 1.0 == 0.0) toLong().toString() else "%.2f".format(java.util.Locale.US, this).trimEnd('0').trimEnd('.')
