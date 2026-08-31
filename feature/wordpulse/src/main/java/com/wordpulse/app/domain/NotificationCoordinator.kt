package com.wordpulse.app.domain

enum class TypingAlertType(val priority: Int) {
    Success(10),
    InvalidInput(60),
    ModeratePerformanceDeviation(70),
    MarkedPerformanceDeviation(80),
    Duplicate(90),
    Error(100),
}

data class TypingAlert(
    val id: Long,
    val type: TypingAlertType,
    val message: String,
    val dismissible: Boolean,
    val action: TypingAlertAction? = null,
)

sealed interface TypingAlertAction {
    data class EditSubmission(
        val insertedEntryId: Long,
        val correctionToken: Long,
    ) : TypingAlertAction
}

data class DuplicateAlertInput(
    val totalOccurrences: Int,
    val previousOccurrenceUtcMs: Long,
    val insertedEntryId: Long,
    val correctionToken: Long,
)

class NotificationCoordinator(
    private val formatter: TypingInsightFormatter,
    private val recentWindowSize: Int = 6,
    private val requiredDeviationsInWindow: Int = 4,
    private val performanceCooldownSubmissions: Int = 6,
    private val recoveryNormalSamples: Int = 4,
) {
    private val recentEvaluations = ArrayDeque<TypingPerformanceEvaluation>()
    private var activeAlert: TypingAlert? = null
    private var nextAlertId = 0L
    private var submissionIndex = 0
    private var lastPerformanceAlertIndex: Int? = null
    private var latchedPerformanceSignature: String? = null
    private var normalSamplesSincePerformanceAlert = 0

    fun currentAlert(): TypingAlert? = activeAlert

    fun onInvalidInput(reason: InvalidInputReason): TypingAlert? =
        offer(
            TypingAlert(
                id = nextId(),
                type = TypingAlertType.InvalidInput,
                message = invalidInputMessage(reason),
                dismissible = false,
            ),
        )

    fun onSubmission(
        duplicate: DuplicateAlertInput?,
        evaluation: TypingPerformanceEvaluation?,
        successMessage: String,
        editAction: TypingAlertAction? = null,
    ): TypingAlert? {
        if (
            activeAlert?.action is TypingAlertAction.EditSubmission ||
            activeAlert?.type == TypingAlertType.InvalidInput ||
            activeAlert?.type == TypingAlertType.Success
        ) {
            activeAlert = null
        }
        submissionIndex += 1
        evaluation?.let(::recordEvaluation)

        val candidate = when {
            duplicate != null -> TypingAlert(
                id = nextId(),
                type = TypingAlertType.Duplicate,
                message = formatter.duplicate(
                    totalOccurrences = duplicate.totalOccurrences,
                    previousOccurrenceUtcMs = duplicate.previousOccurrenceUtcMs,
                ),
                dismissible = true,
                action = TypingAlertAction.EditSubmission(
                    insertedEntryId = duplicate.insertedEntryId,
                    correctionToken = duplicate.correctionToken,
                ),
            )
            evaluation != null -> performanceAlert(evaluation, editAction)
            else -> null
        } ?: TypingAlert(
            id = nextId(),
            type = TypingAlertType.Success,
            message = successMessage,
            dismissible = false,
            action = editAction,
        )
        return offer(candidate)
    }

    fun onError(message: String): TypingAlert? =
        offer(
            TypingAlert(
                id = nextId(),
                type = TypingAlertType.Error,
                message = message,
                dismissible = true,
            ),
        )

    fun dismiss(alertId: Long) {
        if (activeAlert?.id == alertId) activeAlert = null
    }

    private fun recordEvaluation(evaluation: TypingPerformanceEvaluation) {
        recentEvaluations.addLast(evaluation)
        while (recentEvaluations.size > recentWindowSize) recentEvaluations.removeFirst()
        if (evaluation.level == TypingDeviationLevel.Normal) {
            normalSamplesSincePerformanceAlert += 1
            if (normalSamplesSincePerformanceAlert >= recoveryNormalSamples) {
                latchedPerformanceSignature = null
            }
        } else if (evaluation.level != TypingDeviationLevel.InsufficientData) {
            normalSamplesSincePerformanceAlert = 0
        }
    }

    private fun performanceAlert(
        latest: TypingPerformanceEvaluation,
        editAction: TypingAlertAction?,
    ): TypingAlert? {
        if (latest.level in setOf(TypingDeviationLevel.Normal, TypingDeviationLevel.InsufficientData)) {
            return null
        }

        val comparable = recentEvaluations.filter { it.lengthBand == latest.lengthBand }
        val deviations = comparable.count {
            it.level == TypingDeviationLevel.ModerateDeviation ||
                it.level == TypingDeviationLevel.MarkedDeviation
        }
        val extremeIndividual = latest.level == TypingDeviationLevel.MarkedDeviation &&
            latest.signals.size >= 2 &&
            latest.signals.maxOfOrNull { it.robustScore }?.let { it >= EXTREME_INDIVIDUAL_SCORE } == true
        if (!extremeIndividual && (comparable.size < recentWindowSize || deviations < requiredDeviationsInWindow)) {
            return null
        }

        val signature = latest.signals.map { it.kind }.sortedBy(Enum<*>::ordinal).joinToString(",")
        val cooldownActive = lastPerformanceAlertIndex?.let {
            submissionIndex - it < performanceCooldownSubmissions
        } ?: false
        if (cooldownActive || latchedPerformanceSignature == signature) return null

        val markedInWindow = comparable.count { it.level == TypingDeviationLevel.MarkedDeviation }
        val type = if (
            latest.level == TypingDeviationLevel.MarkedDeviation ||
            markedInWindow >= requiredDeviationsInWindow
        ) {
            TypingAlertType.MarkedPerformanceDeviation
        } else {
            TypingAlertType.ModeratePerformanceDeviation
        }
        lastPerformanceAlertIndex = submissionIndex
        latchedPerformanceSignature = signature
        normalSamplesSincePerformanceAlert = 0
        return TypingAlert(
            id = nextId(),
            type = type,
            message = formatter.performance(latest),
            dismissible = true,
            action = editAction,
        )
    }

    private fun offer(candidate: TypingAlert): TypingAlert? {
        val current = activeAlert
        if (current != null && current.type.priority > candidate.type.priority) return null
        activeAlert = candidate
        return candidate
    }

    private fun invalidInputMessage(reason: InvalidInputReason): String {
        val detail = when (reason) {
            InvalidInputReason.Uppercase -> "le maiuscole non sono consentite"
            InvalidInputReason.Whitespace -> "spazi e invii non sono consentiti nel testo"
            InvalidInputReason.Number -> "i numeri non sono consentiti"
            InvalidInputReason.Symbol -> "punteggiatura e simboli non sono consentiti"
            InvalidInputReason.NonAscii -> "accenti, emoji e caratteri non ASCII non sono consentiti"
        }
        return "Usa solo lettere lowercase da a a z: $detail."
    }

    private fun nextId(): Long = ++nextAlertId

    private companion object {
        const val EXTREME_INDIVIDUAL_SCORE = 6.0
    }
}
