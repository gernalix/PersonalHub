package com.gernalix.sostanze.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max

data class SubstancePlan(
    val id: Long,
    val name: String,
    val type: String,
    val stockCurrent: Double,
    val stockUnit: String,
    val dosePerIntake: Double,
    val doseUnit: String,
    val dailyFrequency: Int,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val forever: Boolean,
    val archived: Boolean,
    val prn: Boolean,
    val doseTimesCsv: String = "",
    val daysMask: Int = 127,
)

data class IntakeRecord(
    val id: Long,
    val substanceId: Long,
    val timestampMs: Long,
    val dose: Double,
    val quantity: Double = 1.0,
)

data class InteractionRulePlan(
    val id: Long,
    val sourceSubstanceId: Long,
    val avoidBeforeHours: Double,
    val avoidAfterHours: Double,
    val enforcement: InteractionEnforcementMode,
    val allPresentAndFuture: Boolean,
    val targetSubstanceIds: Set<Long>,
)

enum class InteractionEnforcementMode { BLOCK, WARN }

enum class DoseSection {
    DUE_TODAY,
    LATER,
    BLOCKED,
    TAKEN,
    PRN,
    ARCHIVED
}

data class InteractionBlock(
    val ruleId: Long,
    val sourceSubstanceId: Long,
    val sourceName: String,
    val untilMs: Long,
    val remainingMs: Long,
    val warningOnly: Boolean,
)

data class DoseButtonState(
    val substance: SubstancePlan,
    val section: DoseSection,
    val dosesDoneToday: Int,
    val dosesPlannedToday: Int,
    val nextIdealMs: Long?,
    val canRecord: Boolean,
    val block: InteractionBlock? = null,
    val blocks: List<InteractionBlock> = block?.let(::listOf) ?: emptyList(),
)

data class StockCoverage(
    val dailyConsumption: Double,
    val daysCovered: Double?,
    val exhaustionDate: LocalDate?,
)

data class NotificationPlan(
    val kind: String,
    val entityId: Long,
    val scheduledForMs: Long,
)

object SostanzeEngine {
    fun doseState(
        substance: SubstancePlan,
        intakes: List<IntakeRecord>,
        rules: List<InteractionRulePlan>,
        allSubstances: List<SubstancePlan>,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DoseButtonState {
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val todaysIntakes = intakes
            .filter { it.substanceId == substance.id && isSameDay(it.timestampMs, nowMs, zoneId) }
            .sortedBy { it.timestampMs }
        val regimenActive = isRegimenActive(substance, today)
        val explicitTimes = if (regimenActive) scheduledTimesMs(substance, today, zoneId) else emptyList()
        val planned = if (!regimenActive) 0 else explicitTimes.size.takeIf { it > 0 } ?: max(0, substance.dailyFrequency)
        val completed = todaysIntakes.sumOf { it.quantity }.toInt().coerceAtLeast(0)
        val blocks = activeInteractionBlocks(substance, intakes, rules, allSubstances, nowMs)
        val block = blocks.firstOrNull { !it.warningOnly } ?: blocks.firstOrNull()
        val nextIdeal = if (explicitTimes.isNotEmpty()) explicitTimes.getOrNull(completed) else nextIdealMs(todaysIntakes, planned, nowMs, zoneId)

        val section = when {
            substance.archived -> DoseSection.ARCHIVED
            substance.prn -> DoseSection.PRN
            !regimenActive -> DoseSection.LATER
            block?.warningOnly == false -> DoseSection.BLOCKED
            completed >= planned -> DoseSection.TAKEN
            nextIdeal != null && nowMs < nextIdeal -> DoseSection.LATER
            else -> DoseSection.DUE_TODAY
        }
        return DoseButtonState(
            substance = substance,
            section = section,
            dosesDoneToday = completed.coerceAtMost(planned),
            dosesPlannedToday = planned,
            nextIdealMs = nextIdeal,
            // BLOCK is enforced by the command after a tap; the action must remain visible/tappable.
            canRecord = section != DoseSection.ARCHIVED && section != DoseSection.TAKEN,
            block = block,
            blocks = blocks,
        )
    }

    fun nextIdealMs(
        todaysIntakes: List<IntakeRecord>,
        dailyFrequency: Int,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (dailyFrequency <= 0 || todaysIntakes.size >= dailyFrequency) return null
        if (todaysIntakes.isEmpty()) return nowMs
        val intervalMs = (24.hoursMs / dailyFrequency).toLong()
        return todaysIntakes.maxOf { it.timestampMs } + intervalMs
    }

    fun isRegimenActive(substance: SubstancePlan, date: LocalDate): Boolean {
        if (substance.archived || date.toEpochDay() < substance.startEpochDay) return false
        if (!substance.forever && substance.endEpochDay?.let { date.toEpochDay() > it } == true) return false
        val bit = 1 shl (date.dayOfWeek.value - 1)
        return substance.prn || substance.daysMask and bit != 0
    }

    fun scheduledTimesMs(substance: SubstancePlan, date: LocalDate, zoneId: ZoneId): List<Long> =
        substance.doseTimesCsv.split(',').mapNotNull { value ->
            runCatching { java.time.LocalTime.parse(value.trim()) }.getOrNull()
        }.distinct().sorted().map { date.atTime(it).atZone(zoneId).toInstant().toEpochMilli() }

    fun depletionDate(remainingDoses: Int, frequencyCount: Int, frequencyPeriod: String, today: LocalDate): LocalDate? {
        if (remainingDoses < 0 || frequencyCount <= 0 || frequencyPeriod !in setOf("DAY", "WEEK")) return null
        val days = if (frequencyPeriod == "DAY") {
            ceil(remainingDoses / frequencyCount.toDouble()).toLong()
        } else {
            ceil(remainingDoses / frequencyCount.toDouble()).toLong() * 7L
        }
        return today.plusDays(days)
    }

    fun applyIntakeStock(stock: Double, dose: Double): Double =
        (stock - dose).coerceAtLeast(0.0)

    fun applyStockAdjustment(stock: Double, delta: Double): Double =
        (stock + delta).coerceAtLeast(0.0)

    fun stockCoverage(substance: SubstancePlan, today: LocalDate = LocalDate.now()): StockCoverage {
        val dailyConsumption = if (substance.prn) 0.0 else substance.dosePerIntake * substance.dailyFrequency.coerceAtLeast(0)
        if (dailyConsumption <= 0.0) {
            return StockCoverage(dailyConsumption = dailyConsumption, daysCovered = null, exhaustionDate = null)
        }
        val days = substance.stockCurrent / dailyConsumption
        return StockCoverage(
            dailyConsumption = dailyConsumption,
            daysCovered = days,
            exhaustionDate = today.plusDays(ceil(days).toLong()),
        )
    }

    fun nextRefillDate(prescriptionEpochDay: Long, refillEveryMonths: Int): LocalDate =
        LocalDate.ofEpochDay(prescriptionEpochDay).plusMonths(refillEveryMonths.toLong())

    fun activeInteractionBlock(
        target: SubstancePlan,
        intakes: List<IntakeRecord>,
        rules: List<InteractionRulePlan>,
        allSubstances: List<SubstancePlan>,
        nowMs: Long,
    ): InteractionBlock? = activeInteractionBlocks(target, intakes, rules, allSubstances, nowMs).firstOrNull()

    fun activeInteractionBlocks(
        target: SubstancePlan,
        intakes: List<IntakeRecord>,
        rules: List<InteractionRulePlan>,
        allSubstances: List<SubstancePlan>,
        nowMs: Long,
    ): List<InteractionBlock> {
        val namesById = allSubstances.associateBy({ it.id }, { it.name })
        val afterBlocks = rules.asSequence()
            .filter { it.targets(target.id) && it.avoidAfterHours > 0.0 }
            .flatMap { rule ->
                intakes.asSequence()
                    .filter { it.substanceId == rule.sourceSubstanceId }
                    .mapNotNull { intake ->
                        val until = intake.timestampMs + rule.avoidAfterHours.hoursMs.toLong()
                        if (nowMs in intake.timestampMs until until) {
                            InteractionBlock(
                                ruleId = rule.id,
                                sourceSubstanceId = rule.sourceSubstanceId,
                                sourceName = namesById[rule.sourceSubstanceId].orEmpty(),
                                untilMs = until,
                                remainingMs = until - nowMs,
                                warningOnly = rule.enforcement == InteractionEnforcementMode.WARN,
                            )
                        } else {
                            null
                        }
                    }
            }
            .sortedBy { it.untilMs }
            .toList()
        val beforeBlocks = rules.asSequence()
            .filter { it.sourceSubstanceId == target.id && it.avoidBeforeHours > 0.0 }
            .flatMap { rule ->
                intakes.asSequence()
                    .filter { intake -> rule.targets(intake.substanceId) }
                    .mapNotNull { intake ->
                        val since = nowMs - intake.timestampMs
                        val window = rule.avoidBeforeHours.hoursMs.toLong()
                        if (since in 0 until window) {
                            InteractionBlock(
                                ruleId = rule.id,
                                sourceSubstanceId = intake.substanceId,
                                sourceName = namesById[intake.substanceId].orEmpty(),
                                untilMs = intake.timestampMs + window,
                                remainingMs = window - since,
                                warningOnly = rule.enforcement == InteractionEnforcementMode.WARN,
                            )
                        } else {
                            null
                        }
                    }
            }
            .sortedBy { it.untilMs }
            .toList()
        return (afterBlocks + beforeBlocks)
            .distinctBy { Triple(it.ruleId, it.sourceSubstanceId, it.untilMs) }
            .sortedWith(compareBy<InteractionBlock> { it.warningOnly }.thenBy { it.untilMs })
    }

    fun interactionEndNotifications(states: List<DoseButtonState>): List<NotificationPlan> =
        states.flatMap { state ->
            state.blocks.filter { !it.warningOnly }.map { block ->
                NotificationPlan("interaction_end", state.substance.id, block.untilMs)
            }
        }.distinctBy { Triple(it.kind, it.entityId, it.scheduledForMs) }

    fun missedDoseNotifications(states: List<DoseButtonState>, nowMs: Long): List<NotificationPlan> =
        states
            .filter { it.section == DoseSection.DUE_TODAY && it.dosesPlannedToday > 0 && it.dosesDoneToday < it.dosesPlannedToday }
            .map {
                NotificationPlan(
                    kind = "missed_dose",
                    entityId = it.substance.id,
                    scheduledForMs = endOfDayMs(nowMs),
                )
            }

    fun refillNotifications(prescriptions: List<Pair<Long, Long>>, alertEnabled: List<Boolean>): List<NotificationPlan> =
        prescriptions.mapIndexedNotNull { index, pair ->
            if (!alertEnabled.getOrElse(index) { false }) return@mapIndexedNotNull null
            NotificationPlan(kind = "refill", entityId = pair.first, scheduledForMs = pair.second)
        }

    fun countdownText(remainingMs: Long): String {
        val totalMinutes = ceil(remainingMs.coerceAtLeast(0) / 60000.0).toLong()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun InteractionRulePlan.targets(substanceId: Long): Boolean =
        allPresentAndFuture || substanceId in targetSubstanceIds

    private fun isSameDay(aMs: Long, bMs: Long, zoneId: ZoneId): Boolean =
        Instant.ofEpochMilli(aMs).atZone(zoneId).toLocalDate() ==
            Instant.ofEpochMilli(bMs).atZone(zoneId).toLocalDate()

    private fun endOfDayMs(nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        val tomorrow = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate().plusDays(1)
        return tomorrow.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    private val Number.hoursMs: Double get() = toDouble() * 60.0 * 60.0 * 1000.0
}

fun Long.toLocalDate(zoneId: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()

fun LocalDate.startOfDayMs(zoneId: ZoneId = ZoneId.systemDefault()): Long =
    atStartOfDay(zoneId).toInstant().toEpochMilli()

fun ZonedDateTime.truncatedToMinuteMs(): Long =
    truncatedTo(ChronoUnit.MINUTES).toInstant().toEpochMilli()
