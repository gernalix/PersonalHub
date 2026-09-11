package com.gernalix.sostanze.data

import androidx.room.withTransaction
import com.gernalix.sostanze.domain.InteractionEnforcementMode
import com.gernalix.sostanze.domain.InteractionRulePlan
import com.gernalix.sostanze.domain.IntakeRecord
import com.gernalix.sostanze.domain.SostanzeEngine
import com.gernalix.sostanze.domain.SubstancePlan
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class SostanzeSnapshot(
    val substances: List<SubstanceEntity> = emptyList(),
    val intakes: List<IntakeEventEntity> = emptyList(),
    val stockAdjustments: List<StockAdjustmentEntity> = emptyList(),
    val prescriptions: List<PrescriptionEntity> = emptyList(),
    val prescriptionDetails: List<PrescriptionDetail> = emptyList(),
    val interactionRules: List<InteractionRuleEntity> = emptyList(),
    val interactionTargets: List<InteractionTargetEntity> = emptyList(),
    val notifications: List<NotificationStateEntity> = emptyList(),
    val macros: List<MacroEntity> = emptyList(),
    val macroItems: List<MacroItemEntity> = emptyList(),
)

class SostanzeRepository(private val db: SostanzeDatabase) {
    private val dao = db.dao()

    val homeSnapshot: Flow<SostanzeSnapshot> = combine(
        dao.observeSubstances(),
        dao.observeRecentIntakes(),
        dao.observeInteractionRules(),
        dao.observeInteractionTargets(),
        dao.observeMacros(),
        dao.observeMacroItems(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SostanzeSnapshot(
            substances = values[0] as List<SubstanceEntity>,
            intakes = values[1] as List<IntakeEventEntity>,
            interactionRules = values[2] as List<InteractionRuleEntity>,
            interactionTargets = values[3] as List<InteractionTargetEntity>,
            macros = values[4] as List<MacroEntity>,
            macroItems = values[5] as List<MacroItemEntity>,
        )
    }

    val snapshot: Flow<SostanzeSnapshot> = combine(
        dao.observeSubstances(),
        dao.observeRecentIntakes(),
        dao.observeStockAdjustments(),
        dao.observePrescriptionDetails(),
        dao.observeInteractionRules(),
        dao.observeInteractionTargets(),
        dao.observeNotificationState(),
        dao.observeMacros(),
        dao.observeMacroItems(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SostanzeSnapshot(
            substances = values[0] as List<SubstanceEntity>,
            intakes = values[1] as List<IntakeEventEntity>,
            stockAdjustments = values[2] as List<StockAdjustmentEntity>,
            prescriptionDetails = values[3] as List<PrescriptionDetail>,
            prescriptions = (values[3] as List<PrescriptionDetail>).map { it.prescription },
            interactionRules = values[4] as List<InteractionRuleEntity>,
            interactionTargets = values[5] as List<InteractionTargetEntity>,
            notifications = values[6] as List<NotificationStateEntity>,
            macros = values[7] as List<MacroEntity>,
            macroItems = values[8] as List<MacroItemEntity>,
        )
    }

    /** Production databases intentionally start empty. Demo/personal rows are never seeded. */
    suspend fun initialize() = Unit

    suspend fun substanceById(substanceId: Long): SubstanceEntity? = dao.substanceById(substanceId)

    suspend fun saveSubstance(substance: SubstanceEntity): SubstanceSaveOutcome = db.withTransaction {
        val canonical = canonicalName(substance.name)
        if (canonical.isEmpty()) return@withTransaction SubstanceSaveOutcome.Invalid("name")
        val matches = dao.substancesByCanonicalName(canonical).filter { it.id != substance.id }
        matches.firstOrNull { it.archived }?.let { return@withTransaction SubstanceSaveOutcome.RestoreRequired(it.id) }
        if (matches.isNotEmpty()) return@withTransaction SubstanceSaveOutcome.Duplicate(matches.first().id)
        val normalized = substance.copy(name = substance.name.trim(), canonicalName = canonical).validatedUnits()
        if (normalized.id == 0L) SubstanceSaveOutcome.Saved(dao.insertSubstance(normalized))
        else {
            requireNotNull(dao.substanceById(normalized.id))
            dao.updateSubstance(normalized)
            SubstanceSaveOutcome.Saved(normalized.id)
        }
    }

    suspend fun recordIntake(
        substanceId: Long,
        timestampMs: Long = System.currentTimeMillis(),
        idempotencyKey: String = UUID.randomUUID().toString(),
        allowWarning: Boolean = true,
        quantity: Double = 1.0,
        unit: String? = null,
    ): IntakeOutcome = db.withTransaction {
            val substance = dao.substanceById(substanceId) ?: return@withTransaction IntakeOutcome.NotFound
            if (substance.archived) return@withTransaction IntakeOutcome.Archived
            if (dao.intakeCountForKey(idempotencyKey) > 0) return@withTransaction IntakeOutcome.Duplicate
            if (quantity <= 0.0) return@withTransaction IntakeOutcome.InvalidQuantity
            val requestedUnit = unit ?: substance.doseUnit
            if (!substance.stockUnit.equals(substance.doseUnit, ignoreCase = true) || !requestedUnit.equals(substance.doseUnit, ignoreCase = true)) return@withTransaction IntakeOutcome.UnsupportedUnits
            val appliedDose = substance.dosePerIntake * quantity
            if (substance.dosePerIntake <= 0.0) return@withTransaction IntakeOutcome.InvalidQuantity
            val plans = dao.allSubstances().map { it.toPlan() }
            val intakes = dao.recentIntakes().map { it.toRecord() }
            val rules = dao.allInteractionRules().map { it.toPlan(dao.allInteractionTargets()) }
            val state = SostanzeEngine.doseState(substance.toPlan(), intakes, rules, plans, timestampMs)
            val block = state.block
            if (block != null && !block.warningOnly) return@withTransaction IntakeOutcome.Blocked(block.sourceName, block.untilMs)
            if (block?.warningOnly == true && !allowWarning) return@withTransaction IntakeOutcome.Warning(block.sourceName, block.untilMs)
            if (state.nextIdealMs != null && timestampMs < state.nextIdealMs && !substance.prn) {
                return@withTransaction IntakeOutcome.Early(state.nextIdealMs)
            }
            if (state.dosesPlannedToday > 0 && state.dosesDoneToday >= state.dosesPlannedToday) return@withTransaction IntakeOutcome.Duplicate
            val prescription = dao.currentPrescription(substanceId)
            val appliedStockDelta = -minOf(substance.stockCurrent, appliedDose)
            val eventId = dao.insertIntake(
                IntakeEventEntity(
                    substanceId = substanceId,
                    timestampMs = timestampMs,
                    timestampUtc = UtcDateCodec.isoUtc(timestampMs),
                    dose = substance.dosePerIntake,
                    doseUnit = substance.doseUnit,
                    tapGroupId = idempotencyKey,
                    quantity = quantity,
                    appliedStockDelta = appliedStockDelta,
                    prescriptionId = prescription?.id,
                )
            )
            val newStock = substance.stockCurrent + appliedStockDelta
            dao.updateStock(substanceId, newStock)
            dao.insertStockAdjustment(
                StockAdjustmentEntity(
                    substanceId = substanceId,
                    timestampMs = timestampMs,
                    timestampUtc = UtcDateCodec.isoUtc(timestampMs),
                    delta = appliedStockDelta,
                    note = "intake",
                    resultingStock = newStock,
                )
            )
            prescription?.let { dao.updatePrescriptionRemaining(it.id, it.remainingDoses - 1) }
            IntakeOutcome.Recorded(eventId, block?.takeIf { it.warningOnly }?.sourceName)
    }

    suspend fun recordMacro(macroId: Long, timestampMs: Long = System.currentTimeMillis()): List<IntakeOutcome> {
        val groupId = UUID.randomUUID().toString()
        return dao.macroItems(macroId).mapIndexed { index, item ->
            recordIntake(item.substanceId, timestampMs, "$groupId:$index")
        }
    }

    suspend fun undoLastIntake(substanceId: Long): Boolean {
        val ok = db.withTransaction {
            val substance = dao.substanceById(substanceId) ?: return@withTransaction false
            val intake = dao.lastIntakeFor(substanceId) ?: return@withTransaction false
            dao.deleteIntake(intake)
            val restored = -intake.appliedStockDelta
            val newStock = substance.stockCurrent + restored
            dao.updateStock(substanceId, newStock)
            dao.insertStockAdjustment(
                StockAdjustmentEntity(
                    substanceId = substanceId,
                    timestampMs = System.currentTimeMillis(),
                    timestampUtc = UtcDateCodec.isoUtc(System.currentTimeMillis()),
                    delta = restored,
                    note = "undo intake",
                    resultingStock = newStock,
                )
            )
            intake.prescriptionId?.let { id ->
                dao.prescriptionById(id)?.let { dao.updatePrescriptionRemaining(id, (it.remainingDoses + 1).coerceAtMost(it.packageDoseCount)) }
            }
            true
        }
        return ok
    }

    suspend fun undoIntakes(ids: List<Long>): Boolean {
        if (ids.isEmpty()) return false
        val ok = db.withTransaction {
            val intakes = dao.intakesByIds(ids)
            intakes.forEach { intake ->
                val substance = dao.substanceById(intake.substanceId) ?: return@forEach
                dao.deleteIntake(intake)
                val restored = -intake.appliedStockDelta
                val newStock = substance.stockCurrent + restored
                dao.updateStock(intake.substanceId, newStock)
                val now = System.currentTimeMillis()
                dao.insertStockAdjustment(
                    StockAdjustmentEntity(
                        substanceId = intake.substanceId,
                        timestampMs = now,
                        timestampUtc = UtcDateCodec.isoUtc(now),
                        delta = restored,
                        note = "undo tap",
                        resultingStock = newStock,
                    )
                )
                intake.prescriptionId?.let { id ->
                    dao.prescriptionById(id)?.let { dao.updatePrescriptionRemaining(id, (it.remainingDoses + 1).coerceAtMost(it.packageDoseCount)) }
                }
            }
            intakes.isNotEmpty()
        }
        return ok
    }

    suspend fun deleteIntake(id: Long): Boolean = undoIntakes(listOf(id))

    suspend fun editIntake(id: Long, timestampMs: Long, quantity: Double): IntakeEditOutcome = db.withTransaction {
        if (quantity <= 0.0) return@withTransaction IntakeEditOutcome.Invalid
        val intake = dao.intakeById(id) ?: return@withTransaction IntakeEditOutcome.NotFound
        val substance = dao.substanceById(intake.substanceId) ?: return@withTransaction IntakeEditOutcome.NotFound
        if (!substance.stockUnit.equals(intake.doseUnit, ignoreCase = true)) return@withTransaction IntakeEditOutcome.UnsupportedUnits
        val newApplied = -(intake.dose * quantity)
        val correctedStock = substance.stockCurrent - intake.appliedStockDelta + newApplied
        if (correctedStock < 0.0) return@withTransaction IntakeEditOutcome.InsufficientStock
        dao.updateIntake(
            intake.copy(
                timestampMs = timestampMs,
                timestampUtc = UtcDateCodec.isoUtc(timestampMs),
                quantity = quantity,
                appliedStockDelta = newApplied,
            )
        )
        dao.updateStock(intake.substanceId, correctedStock)
        val now = System.currentTimeMillis()
        dao.insertStockAdjustment(
            StockAdjustmentEntity(
                substanceId = intake.substanceId,
                timestampMs = now,
                timestampUtc = UtcDateCodec.isoUtc(now),
                delta = newApplied - intake.appliedStockDelta,
                note = "edit intake:$id",
                resultingStock = correctedStock,
            )
        )
        IntakeEditOutcome.Updated
    }

    suspend fun historyPage(limit: Int = 100, offset: Int = 0): List<IntakeEventEntity> =
        dao.historyPage(limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    suspend fun adjustStock(substanceId: Long, delta: Double, note: String?): StockOutcome = db.withTransaction {
            val substance = dao.substanceById(substanceId) ?: return@withTransaction StockOutcome.NotFound
            if (delta == 0.0) return@withTransaction StockOutcome.NoChange
            val newStock = substance.stockCurrent + delta
            if (newStock < 0.0) return@withTransaction StockOutcome.Insufficient(substance.stockCurrent)
            dao.updateStock(substanceId, newStock)
            dao.insertStockAdjustment(
                StockAdjustmentEntity(
                    substanceId = substanceId,
                    timestampMs = System.currentTimeMillis(),
                    timestampUtc = UtcDateCodec.isoUtc(System.currentTimeMillis()),
                    delta = delta,
                    note = note?.takeIf { it.isNotBlank() },
                    resultingStock = newStock,
                )
            )
            StockOutcome.Applied(delta, newStock)
    }

    suspend fun setStock(substanceId: Long, target: Double, note: String? = "set stock"): StockOutcome {
        require(target >= 0.0)
        val current = dao.substanceById(substanceId)?.stockCurrent ?: return StockOutcome.NotFound
        return adjustStock(substanceId, target - current, note)
    }

    suspend fun savePrescription(prescription: PrescriptionEntity): Long = db.withTransaction {
        require(prescription.packageDoseCount > 0 && prescription.remainingDoses in 0..prescription.packageDoseCount)
        require(prescription.doseMg > 0.0 && prescription.frequencyCount > 0)
        require(prescription.frequencyPeriod == "DAY" || prescription.frequencyPeriod == "WEEK")
        val value = prescription.copy(
                prescriptionDateUtc = UtcDateCodec.prescriptionIsoUtc(prescription.prescriptionEpochDay)
            )
        if (value.id == 0L) dao.insertPrescription(value) else { dao.updatePrescription(value); value.id }
    }

    suspend fun createPrescription(draft: PrescriptionDraft): Long = db.withTransaction {
        val canonical = canonicalName(draft.name)
        require(canonical.isNotEmpty())
        val matches = dao.substancesByCanonicalName(canonical)
        val substanceId = matches.firstOrNull { !it.archived }?.id
            ?: matches.firstOrNull()?.let { archived -> dao.restoreSubstance(archived.id); archived.id }
            ?: dao.insertSubstance(
                SubstanceEntity(
                    name = draft.name.trim(), canonicalName = canonical, type = SubstanceTypes.FARMACO,
                    stockCurrent = 0.0, stockUnit = "mg", dosePerIntake = draft.doseMg, doseUnit = "mg",
                    dailyFrequency = if (draft.frequencyPeriod == "DAY") draft.frequencyCount else 0,
                    startEpochDay = LocalDate.now().toEpochDay(), forever = true,
                )
            )
        savePrescription(
            PrescriptionEntity(
                substanceId = substanceId,
                prescriptionEpochDay = draft.prescriptionEpochDay,
                quantityPrescribed = draft.packageDoseCount.toDouble(),
                refillEveryMonths = 1,
                orderEpochDay = draft.orderEpochDay,
                packageDoseCount = draft.packageDoseCount,
                remainingDoses = draft.remainingDoses,
                doseMg = draft.doseMg,
                frequencyPeriod = draft.frequencyPeriod,
                frequencyCount = draft.frequencyCount,
                doctorContactId = draft.doctorContactId,
                financeTransactionId = draft.financeTransactionId,
            )
        )
    }

    suspend fun prescriptionPrefill(substanceId: Long): PrescriptionEntity? = dao.latestPrescription(substanceId)
    suspend fun prescriptionPrefill(name: String): PrescriptionEntity? =
        dao.substancesByCanonicalName(canonicalName(name)).firstOrNull()?.let { dao.latestPrescription(it.id) }
    suspend fun doctorChoices(query: String): List<DoctorChoice> = dao.doctorChoices(query.trim())
    suspend fun doctorName(contactId: Long): String? = dao.doctorName(contactId)
    suspend fun recentMatchingCosts(name: String): List<CostChoice> = dao.recentMatchingCosts(name)
    suspend fun costAmount(transactionId: Long): String? = dao.costAmount(transactionId)

    suspend fun deletePrescription(id: Long) = db.withTransaction { dao.deletePrescription(id) }

    suspend fun saveInteractionRule(
        rule: InteractionRuleEntity,
        targetKind: String,
        targetSubstanceId: Long?,
    ) {
        db.withTransaction {
            require(rule.sourceSubstanceId != targetSubstanceId)
            require(rule.avoidBeforeHours >= 0.0 && rule.avoidAfterHours >= 0.0)
            val ruleId = if (rule.id == 0L) dao.upsertInteractionRule(rule) else {
                dao.updateInteractionRule(rule)
                rule.id
            }
            dao.deleteInteractionTargets(ruleId)
            dao.upsertInteractionTarget(
                InteractionTargetEntity(
                    ruleId = ruleId,
                    targetSubstanceId = targetSubstanceId,
                    targetKind = targetKind,
                )
            )
        }
    }

    suspend fun deleteInteractionRule(ruleId: Long) = db.withTransaction { dao.deleteInteractionRule(ruleId) }

    suspend fun saveMacro(macro: MacroEntity, substanceIds: List<Long>): Long = db.withTransaction {
        require(macro.name.isNotBlank() && substanceIds.isNotEmpty())
        val id = dao.upsertMacro(macro.copy(name = macro.name.trim()))
        dao.deleteMacroItems(id)
        substanceIds.distinct().forEach { dao.upsertMacroItem(MacroItemEntity(macroId = id, substanceId = it)) }
        id
    }

    suspend fun deleteMacro(macroId: Long) = db.withTransaction { dao.deleteMacro(macroId) }

    suspend fun saveNotifications(plans: List<com.gernalix.sostanze.domain.NotificationPlan>) {
        plans.forEach { plan ->
            dao.upsertNotificationState(
                NotificationStateEntity(
                    kind = plan.kind,
                    entityId = plan.entityId,
                    scheduledForMs = plan.scheduledForMs,
                    scheduledForUtc = UtcDateCodec.isoUtc(plan.scheduledForMs),
                )
            )
        }
    }

    suspend fun archiveSubstance(substanceId: Long) {
        dao.archiveSubstance(substanceId)
        com.gernalix.personalhub.core.hubcontext.HubContextRuntime.canonicalLifecycleChangedIfInitialized(
            com.gernalix.personalhub.contracts.database.HubEntityRef("substances", "substance", substanceId.toString()),
        )
    }

    suspend fun restoreSubstance(substanceId: Long) {
        db.withTransaction { dao.restoreSubstance(substanceId) }
        com.gernalix.personalhub.core.hubcontext.HubContextRuntime.canonicalLifecycleChangedIfInitialized(
            com.gernalix.personalhub.contracts.database.HubEntityRef("substances", "substance", substanceId.toString()),
        )
    }
}

data class IntakeUndoToken(val intakeIds: List<Long>)

data class PrescriptionDraft(
    val name: String,
    val packageDoseCount: Int,
    val remainingDoses: Int = packageDoseCount,
    val doseMg: Double,
    val frequencyPeriod: String,
    val frequencyCount: Int,
    val orderEpochDay: Long = LocalDate.now().toEpochDay(),
    val prescriptionEpochDay: Long = LocalDate.now().toEpochDay(),
    val doctorContactId: Long? = null,
    val financeTransactionId: Long? = null,
)

fun SubstanceEntity.toPlan(): SubstancePlan =
    SubstancePlan(
        id = id,
        name = name,
        type = type,
        stockCurrent = stockCurrent,
        stockUnit = stockUnit,
        dosePerIntake = dosePerIntake,
        doseUnit = doseUnit,
        dailyFrequency = dailyFrequency,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
        forever = forever,
        archived = archived,
        prn = prn,
        doseTimesCsv = doseTimesCsv,
        daysMask = daysMask,
    )

private fun SubstanceEntity.validatedUnits(): SubstanceEntity {
    require(stockCurrent >= 0.0 && dosePerIntake > 0.0)
    require(stockUnit.isNotBlank() && doseUnit.isNotBlank())
    return this
}

fun canonicalName(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)

sealed interface SubstanceSaveOutcome {
    data class Saved(val id: Long) : SubstanceSaveOutcome
    data class Duplicate(val existingId: Long) : SubstanceSaveOutcome
    data class RestoreRequired(val archivedId: Long) : SubstanceSaveOutcome
    data class Invalid(val field: String) : SubstanceSaveOutcome
}

sealed interface IntakeOutcome {
    data class Recorded(val id: Long, val warningSource: String? = null) : IntakeOutcome
    data class Blocked(val source: String, val untilMs: Long) : IntakeOutcome
    data class Early(val recommendedAtMs: Long) : IntakeOutcome
    data class Warning(val source: String, val untilMs: Long) : IntakeOutcome
    data object InsufficientStock : IntakeOutcome
    data object UnsupportedUnits : IntakeOutcome
    data object InvalidQuantity : IntakeOutcome
    data object Duplicate : IntakeOutcome
    data object Archived : IntakeOutcome
    data object NotFound : IntakeOutcome
}

sealed interface StockOutcome {
    data class Applied(val actualDelta: Double, val resultingStock: Double) : StockOutcome
    data class Insufficient(val available: Double) : StockOutcome
    data object NoChange : StockOutcome
    data object NotFound : StockOutcome
}

sealed interface IntakeEditOutcome {
    data object Updated : IntakeEditOutcome
    data object Invalid : IntakeEditOutcome
    data object NotFound : IntakeEditOutcome
    data object UnsupportedUnits : IntakeEditOutcome
    data object InsufficientStock : IntakeEditOutcome
}

fun IntakeEventEntity.toRecord(): IntakeRecord =
    IntakeRecord(
        id = id,
        substanceId = substanceId,
        timestampMs = timestampMs,
        dose = dose,
        quantity = quantity,
    )

fun InteractionRuleEntity.toPlan(targets: List<InteractionTargetEntity>): InteractionRulePlan {
    val ruleTargets = targets.filter { it.ruleId == id }
    return InteractionRulePlan(
        id = id,
        sourceSubstanceId = sourceSubstanceId,
        avoidBeforeHours = avoidBeforeHours,
        avoidAfterHours = avoidAfterHours,
        enforcement = if (enforcement == InteractionEnforcement.WARN) {
            InteractionEnforcementMode.WARN
        } else {
            InteractionEnforcementMode.BLOCK
        },
        allPresentAndFuture = ruleTargets.any { it.targetKind == InteractionTargetKinds.ALL_PRESENT_AND_FUTURE },
        targetSubstanceIds = ruleTargets.mapNotNull { it.targetSubstanceId }.toSet(),
    )
}
