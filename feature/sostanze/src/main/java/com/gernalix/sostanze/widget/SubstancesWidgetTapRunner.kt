package com.gernalix.sostanze.widget

import com.gernalix.sostanze.data.IntakeOutcome
import com.gernalix.sostanze.data.SostanzeRepository

sealed class SubstancesWidgetTapResult {
    data class Recorded(val title: String, val intakeId: Long) : SubstancesWidgetTapResult()
    data object Unavailable : SubstancesWidgetTapResult()
    data object Failed : SubstancesWidgetTapResult()
}

class SubstancesWidgetTapRunner(
    private val repository: SostanzeRepository,
    private val afterSuccessfulWrite: () -> Unit
) {
    suspend fun run(substanceId: Long): SubstancesWidgetTapResult =
        runCatching {
            val substance = repository.substanceById(substanceId)
                ?.takeIf { !it.archived }
                ?: return@runCatching SubstancesWidgetTapResult.Unavailable
            when (val outcome = repository.recordIntake(substanceId)) {
                is IntakeOutcome.Recorded -> {
                    afterSuccessfulWrite()
                    SubstancesWidgetTapResult.Recorded(substance.name, outcome.id)
                }
                IntakeOutcome.Archived,
                IntakeOutcome.NotFound -> SubstancesWidgetTapResult.Unavailable
                else -> SubstancesWidgetTapResult.Failed
            }
        }.getOrElse { SubstancesWidgetTapResult.Failed }
}
