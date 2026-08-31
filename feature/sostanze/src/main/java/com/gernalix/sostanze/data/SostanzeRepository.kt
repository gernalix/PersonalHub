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
    val interactionRules: List<InteractionRuleEntity> = emptyList(),
    val interactionTargets: List<InteractionTargetEntity> = emptyList(),
    val notifications: List<NotificationStateEntity> = emptyList(),
    val macros: List<MacroEntity> = emptyList(),
    val macroItems: List<MacroItemEntity> = emptyList(),
)

class SostanzeRepository(private val db: SostanzeDatabase) {
    private val dao = db.dao()

    val snapshot: Flow<SostanzeSnapshot> = combine(
        dao.observeSubstances(),
        dao.observeAllIntakes(),
        dao.observeStockAdjustments(),
        dao.observePrescriptions(),
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
            prescriptions = values[3] as List<PrescriptionEntity>,
            interactionRules = values[4] as List<InteractionRuleEntity>,
            interactionTargets = values[5] as List<InteractionTargetEntity>,
            notifications = values[6] as List<NotificationStateEntity>,
            macros = values[7] as List<MacroEntity>,
            macroItems = values[8] as List<MacroItemEntity>,
        )
    }

    suspend fun seedIfEmpty(nowMs: Long = System.currentTimeMillis()) {
        if (dao.substanceCount() > 0) {
            ensureDefaultMacroIfMissing()
            return
        }
        db.withTransaction {
            val today = LocalDate.now().toEpochDay()
            val pregabalin = dao.upsertSubstance(
                SubstanceEntity(
                    name = "Pregabalin",
                    type = SubstanceTypes.FARMACO,
                    stockCurrent = 30.0,
                    stockUnit = "mg",
                    dosePerIntake = 75.0,
                    doseUnit = "mg",
                    dailyFrequency = 3,
                    startEpochDay = today,
                    forever = true,
                )
            )
            val vitaminD = dao.upsertSubstance(
                SubstanceEntity(
                    name = "Vitamina D",
                    type = SubstanceTypes.INTEGRATORE,
                    stockCurrent = 20000.0,
                    stockUnit = "mg",
                    dosePerIntake = 25.0,
                    doseUnit = "mg",
                    dailyFrequency = 1,
                    startEpochDay = today,
                    forever = true,
                )
            )
            val psyllium = dao.upsertSubstance(
                SubstanceEntity(
                    name = "Psyllium",
                    type = SubstanceTypes.INTEGRATORE,
                    stockCurrent = 250.0,
                    stockUnit = "mg",
                    dosePerIntake = 5.0,
                    doseUnit = "mg",
                    dailyFrequency = 1,
                    startEpochDay = today,
                    forever = true,
                )
            )
            val ibuprofene = dao.upsertSubstance(
                SubstanceEntity(
                    name = "Ibuprofene",
                    type = SubstanceTypes.FARMACO,
                    stockCurrent = 2400.0,
                    stockUnit = "mg",
                    dosePerIntake = 200.0,
                    doseUnit = "mg",
                    dailyFrequency = 0,
                    startEpochDay = today,
                    forever = true,
                    prn = true,
                )
            )
            val prep = dao.upsertSubstance(
                SubstanceEntity(
                    name = "Prep",
                    type = SubstanceTypes.FARMACO,
                    stockCurrent = 9000.0,
                    stockUnit = "mg",
                    dosePerIntake = 300.0,
                    doseUnit = "mg",
                    dailyFrequency = 1,
                    startEpochDay = today,
                    forever = true,
                )
            )
            val tadalafil = dao.upsertSubstance(
                SubstanceEntity(
                    name = "Tadalafil",
                    type = SubstanceTypes.FARMACO,
                    stockCurrent = 280.0,
                    stockUnit = "mg",
                    dosePerIntake = 5.0,
                    doseUnit = "mg",
                    dailyFrequency = 1,
                    startEpochDay = today,
                    forever = true,
                )
            )
            dao.upsertPrescription(
                PrescriptionEntity(
                    substanceId = pregabalin,
                    prescriptionEpochDay = today,
                    prescriptionDateUtc = UtcDateCodec.prescriptionIsoUtc(today),
                    quantityPrescribed = 30.0,
                    refillEveryMonths = 1,
                    alertRefill = true,
                )
            )
            dao.upsertPrescription(
                PrescriptionEntity(
                    substanceId = ibuprofene,
                    prescriptionEpochDay = today,
                    prescriptionDateUtc = UtcDateCodec.prescriptionIsoUtc(today),
                    quantityPrescribed = 2400.0,
                    refillEveryMonths = 1,
                    alertRefill = false,
                )
            )
            val ruleId = dao.upsertInteractionRule(
                InteractionRuleEntity(
                    sourceSubstanceId = psyllium,
                    avoidBeforeHours = 2.0,
                    avoidAfterHours = 2.0,
                    enforcement = InteractionEnforcement.BLOCK,
                )
            )
            dao.upsertInteractionTarget(
                InteractionTargetEntity(
                    ruleId = ruleId,
                    targetKind = InteractionTargetKinds.ALL_PRESENT_AND_FUTURE,
                )
            )
            dao.upsertNotificationState(
                NotificationStateEntity(
                    kind = "seed_created",
                    entityId = vitaminD,
                    scheduledForMs = nowMs,
                    scheduledForUtc = UtcDateCodec.isoUtc(nowMs),
                    sentAtMs = nowMs,
                    sentAtUtc = UtcDateCodec.isoUtc(nowMs),
                )
            )
            val macroId = dao.upsertMacro(MacroEntity(name = "Pillole del mattino"))
            dao.upsertMacroItem(MacroItemEntity(macroId = macroId, substanceId = prep))
            dao.upsertMacroItem(MacroItemEntity(macroId = macroId, substanceId = tadalafil))
        }
    }

    private suspend fun ensureDefaultMacroIfMissing() {
        if (dao.macroCount() > 0) return
        db.withTransaction {
            val today = LocalDate.now().toEpochDay()
            val prep = dao.upsertSubstance(
                SubstanceEntity(
                    name = "Prep",
                    type = SubstanceTypes.FARMACO,
                    stockCurrent = 9000.0,
                    stockUnit = "mg",
                    dosePerIntake = 300.0,
                    doseUnit = "mg",
                    dailyFrequency = 1,
                    startEpochDay = today,
                    forever = true,
                )
            )
            val tadalafil = dao.upsertSubstance(
                SubstanceEntity(
                    name = "Tadalafil",
                    type = SubstanceTypes.FARMACO,
                    stockCurrent = 280.0,
                    stockUnit = "mg",
                    dosePerIntake = 5.0,
                    doseUnit = "mg",
                    dailyFrequency = 1,
                    startEpochDay = today,
                    forever = true,
                )
            )
            val macroId = dao.upsertMacro(MacroEntity(name = "Pillole del mattino"))
            dao.upsertMacroItem(MacroItemEntity(macroId = macroId, substanceId = prep))
            dao.upsertMacroItem(MacroItemEntity(macroId = macroId, substanceId = tadalafil))
        }
    }

    suspend fun saveSubstance(substance: SubstanceEntity): Long {
        val id = dao.upsertSubstance(substance.forceMg())
        return id
    }

    suspend fun recordIntake(substanceId: Long, timestampMs: Long = System.currentTimeMillis()): Long {
        val id = db.withTransaction {
            val substance = dao.substanceById(substanceId) ?: return@withTransaction 0L
            val eventId = dao.insertIntake(
                IntakeEventEntity(
                    substanceId = substanceId,
                    timestampMs = timestampMs,
                    timestampUtc = UtcDateCodec.isoUtc(timestampMs),
                    dose = substance.dosePerIntake,
                    doseUnit = substance.doseUnit,
                    tapGroupId = UUID.randomUUID().toString(),
                )
            )
            val newStock = SostanzeEngine.applyIntakeStock(substance.stockCurrent, substance.dosePerIntake)
            dao.updateStock(substanceId, newStock)
            dao.insertStockAdjustment(
                StockAdjustmentEntity(
                    substanceId = substanceId,
                    timestampMs = timestampMs,
                    timestampUtc = UtcDateCodec.isoUtc(timestampMs),
                    delta = -substance.dosePerIntake,
                    note = "intake",
                    resultingStock = newStock,
                )
            )
            eventId
        }
        return id
    }

    suspend fun recordMacro(macroId: Long, timestampMs: Long = System.currentTimeMillis()): List<Long> {
        val groupId = UUID.randomUUID().toString()
        val ids = db.withTransaction {
            dao.macroItems(macroId).mapNotNull { item ->
                val substance = dao.substanceById(item.substanceId)?.takeIf { !it.archived } ?: return@mapNotNull null
                val eventId = dao.insertIntake(
                    IntakeEventEntity(
                        substanceId = substance.id,
                        timestampMs = timestampMs,
                        timestampUtc = UtcDateCodec.isoUtc(timestampMs),
                        dose = substance.dosePerIntake,
                        doseUnit = substance.doseUnit,
                        tapGroupId = groupId,
                    )
                )
                val newStock = SostanzeEngine.applyIntakeStock(substance.stockCurrent, substance.dosePerIntake)
                dao.updateStock(substance.id, newStock)
                dao.insertStockAdjustment(
                    StockAdjustmentEntity(
                        substanceId = substance.id,
                        timestampMs = timestampMs,
                        timestampUtc = UtcDateCodec.isoUtc(timestampMs),
                        delta = -substance.dosePerIntake,
                        note = "macro",
                        resultingStock = newStock,
                    )
                )
                eventId
            }
        }
        return ids
    }

    suspend fun undoLastIntake(substanceId: Long): Boolean {
        val ok = db.withTransaction {
            val substance = dao.substanceById(substanceId) ?: return@withTransaction false
            val intake = dao.lastIntakeFor(substanceId) ?: return@withTransaction false
            dao.deleteIntake(intake)
            val newStock = substance.stockCurrent + intake.dose
            dao.updateStock(substanceId, newStock)
            dao.insertStockAdjustment(
                StockAdjustmentEntity(
                    substanceId = substanceId,
                    timestampMs = System.currentTimeMillis(),
                    timestampUtc = UtcDateCodec.isoUtc(System.currentTimeMillis()),
                    delta = intake.dose,
                    note = "undo intake",
                    resultingStock = newStock,
                )
            )
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
                val newStock = substance.stockCurrent + intake.dose
                dao.updateStock(intake.substanceId, newStock)
                val now = System.currentTimeMillis()
                dao.insertStockAdjustment(
                    StockAdjustmentEntity(
                        substanceId = intake.substanceId,
                        timestampMs = now,
                        timestampUtc = UtcDateCodec.isoUtc(now),
                        delta = intake.dose,
                        note = "undo tap",
                        resultingStock = newStock,
                    )
                )
            }
            intakes.isNotEmpty()
        }
        return ok
    }

    suspend fun adjustStock(substanceId: Long, delta: Double, note: String?) {
        db.withTransaction {
            val substance = dao.substanceById(substanceId) ?: return@withTransaction
            val newStock = SostanzeEngine.applyStockAdjustment(substance.stockCurrent, delta)
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
        }
    }

    suspend fun savePrescription(prescription: PrescriptionEntity): Long {
        val id = dao.upsertPrescription(
            prescription.copy(
                prescriptionDateUtc = UtcDateCodec.prescriptionIsoUtc(prescription.prescriptionEpochDay)
            )
        )
        return id
    }

    suspend fun saveInteractionRule(
        rule: InteractionRuleEntity,
        targetKind: String,
        targetSubstanceId: Long?,
    ) {
        db.withTransaction {
            val ruleId = dao.upsertInteractionRule(rule)
            dao.upsertInteractionTarget(
                InteractionTargetEntity(
                    ruleId = ruleId,
                    targetSubstanceId = targetSubstanceId,
                    targetKind = targetKind,
                )
            )
        }
    }

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
    }
}

data class IntakeUndoToken(val intakeIds: List<Long>)

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
    )

private fun SubstanceEntity.forceMg(): SubstanceEntity =
    copy(stockUnit = "mg", doseUnit = "mg")

fun IntakeEventEntity.toRecord(): IntakeRecord =
    IntakeRecord(
        id = id,
        substanceId = substanceId,
        timestampMs = timestampMs,
        dose = dose,
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
