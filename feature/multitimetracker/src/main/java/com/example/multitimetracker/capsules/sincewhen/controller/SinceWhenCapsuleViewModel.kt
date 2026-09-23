package com.example.multitimetracker.capsules.sincewhen.controller

import androidx.lifecycle.ViewModel
import com.example.multitimetracker.SingleSubmitGuard
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.capsules.system.SinceWhenCapsuleAccess
import com.example.multitimetracker.capsules.sincewhen.state.SinceWhenHostState
import com.example.multitimetracker.capsules.sincewhen.state.SinceWhenUiState
import com.example.multitimetracker.lifePeriodSubmitKey
import com.example.multitimetracker.model.DEFAULT_LIFE_PERIOD_COLOR_ARGB
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.LifePeriodDisplayUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class SinceWhenCapsuleViewModel(
    private val access: SinceWhenCapsuleAccess
) : ViewModel(), CapsuleRuntimeParticipant {
    override val capsuleId: String = "sincewhen"
    private val lifePeriodSubmitGuard = SingleSubmitGuard()
    private var hostState: SinceWhenHostState = access.hostState()
    private val liveLifePeriods = MutableStateFlow<List<LifePeriod>>(emptyList())
    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<SinceWhenUiState> = _uiState

    init {
        access.observeHostState { host ->
            hostState = host
            refreshUiState()
        }
    }

    override fun onCapsuleRuntimeChanged(context: android.content.Context?, change: CapsuleRuntimeChange) = Unit

    fun lifePeriods(): List<LifePeriod> = liveLifePeriods.value

    fun replaceLifePeriods(periods: List<LifePeriod>) {
        liveLifePeriods.value = periods
        refreshUiState()
    }

    fun addLifePeriod(
        title: String,
        description: String,
        startMs: Long,
        endMs: Long?,
        colorArgb: Long = DEFAULT_LIFE_PERIOD_COLOR_ARGB,
        tagIds: Set<Long> = emptySet(),
        displayUnits: Set<LifePeriodDisplayUnit> = setOf(LifePeriodDisplayUnit.DAYS)
    ) {
        if (access.blockWriteIfNeeded()) return
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        if (endMs != null && endMs <= startMs) return
        if (!lifePeriodSubmitGuard.tryAccept(
                lifePeriodSubmitKey(
                    title = trimmedTitle,
                    description = description,
                    startMs = startMs,
                    endMs = endMs,
                    colorArgb = colorArgb,
                    tagIds = tagIds,
                    displayUnits = displayUnits
                )
            )
        ) return

        val validTagIds = hostState.tags.asSequence().filter { !it.isDeleted }.map { it.id }.toSet()
        liveLifePeriods.update { currentPeriods ->
            val nextId = (currentPeriods.maxOfOrNull { it.id } ?: 0L) + 1L
            currentPeriods + LifePeriod(
                    id = nextId,
                    title = trimmedTitle,
                    description = description.trim(),
                    startMs = startMs,
                    endMs = endMs,
                    colorArgb = colorArgb,
                    tagIds = tagIds.filter { it in validTagIds }.toSet(),
                    displayUnits = displayUnits.ifEmpty { setOf(LifePeriodDisplayUnit.DAYS) }
            )
        }
        access.touchNow()
        refreshUiState()
        access.persist()
        access.scheduleAutoBackup()
        access.syncSharedTagAssignments(liveLifePeriods.value)
    }

    fun updateLifePeriod(
        periodId: Long,
        title: String,
        description: String,
        startMs: Long,
        endMs: Long?,
        colorArgb: Long,
        tagIds: Set<Long>,
        displayUnits: Set<LifePeriodDisplayUnit>
    ) {
        if (access.blockWriteIfNeeded()) return
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        if (endMs != null && endMs <= startMs) return

        val validTagIds = hostState.tags.asSequence().filter { !it.isDeleted }.map { it.id }.toSet()
        liveLifePeriods.update { currentPeriods ->
            currentPeriods.map { period ->
                    if (period.id != periodId) period
                    else period.copy(
                        title = trimmedTitle,
                        description = description.trim(),
                        startMs = startMs,
                        endMs = endMs,
                        colorArgb = colorArgb,
                        tagIds = tagIds.filter { it in validTagIds }.toSet(),
                        displayUnits = displayUnits.ifEmpty { setOf(LifePeriodDisplayUnit.DAYS) }
                    )
                }
        }
        access.touchNow()
        refreshUiState()
        access.persist()
        access.scheduleAutoBackup()
        access.syncSharedTagAssignments(liveLifePeriods.value)
    }

    fun deleteLifePeriod(periodId: Long) {
        if (access.blockWriteIfNeeded()) return
        liveLifePeriods.update { currentPeriods ->
            currentPeriods.filterNot { it.id == periodId }
        }
        access.touchNow()
        refreshUiState()
        access.persist()
        access.scheduleAutoBackup()
        access.syncSharedTagAssignments(liveLifePeriods.value)
    }

    fun endSelectedNow(periodIds: Set<Long>, endMs: Long): Boolean {
        if (access.blockWriteIfNeeded()) return false
        if (periodIds.isEmpty()) return false
        var changed = false
        liveLifePeriods.update { currentPeriods ->
            currentPeriods.map { period ->
                if (period.id in periodIds && period.endMs == null && endMs > period.startMs) {
                    changed = true
                    period.copy(endMs = endMs)
                } else {
                    period
                }
            }
        }
        if (!changed) return false
        access.touchNow()
        refreshUiState()
        access.persist()
        access.scheduleAutoBackup()
        access.syncSharedTagAssignments(liveLifePeriods.value)
        return true
    }

    private fun buildUiState(): SinceWhenUiState {
        return SinceWhenUiState(
            tags = hostState.tags,
            lifePeriods = liveLifePeriods.value,
            nowMs = hostState.nowMs,
        )
    }

    private fun refreshUiState() {
        _uiState.value = buildUiState()
    }
}
