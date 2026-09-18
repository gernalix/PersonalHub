// v336
package com.example.multitimetracker.capsules.chains.controller

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multitimetracker.R
import com.example.multitimetracker.SingleSubmitGuard
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.capsules.chains.public.ChainsSnapshot
import com.example.multitimetracker.capsules.chains.state.ChainsHostState
import com.example.multitimetracker.capsules.chains.state.ChainsUiState
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.capsules.system.ChainsCapsuleAccess
import com.example.multitimetracker.chainSubmitKey
import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.TaskChain
import com.example.multitimetracker.model.TaskChainStep
import com.example.multitimetracker.model.TimeFenceTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.json.JSONObject

class ChainsCapsuleViewModel(
    private val access: ChainsCapsuleAccess
) : ViewModel(), CapsuleRuntimeParticipant {
    override val capsuleId: String = "chains"
    private val chainSubmitGuard = SingleSubmitGuard()
    private val liveSnapshot = MutableStateFlow(ChainsSnapshot.EMPTY)

    val uiState: StateFlow<ChainsUiState> = combine(
        access.hostStateFlow(),
        liveSnapshot,
    ) { host, live ->
        buildUiState(host = host, snapshot = live)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        buildUiState(host = access.hostStateFlow().value, snapshot = ChainsSnapshot.EMPTY),
    )

    override fun onCapsuleRuntimeChanged(context: Context?, change: CapsuleRuntimeChange) = Unit

    fun snapshot(): ChainsSnapshot = liveSnapshot.value

    fun replaceSnapshot(snapshot: ChainsSnapshot) {
        liveSnapshot.value = snapshot
    }

    fun addChain(name: String, steps: List<TaskChainStep> = emptyList()) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull()
        val nm = name.trim().ifBlank { ctx?.getString(R.string.nuova_catena) ?: return }
        if (!chainSubmitGuard.tryAccept(chainSubmitKey(nm, steps))) return
        var createdId = 0L
        liveSnapshot.update { cur ->
            val nextId = (cur.chains.maxOfOrNull { it.id } ?: 0L) + 1L
            createdId = nextId
            val chain = TaskChain(id = nextId, name = nm, steps = steps)
            cur.copy(chains = cur.chains + chain)
        }
        if (ctx != null) {
            access.logUserEvent(
                action = "CHAIN_CREATE",
                entityType = "CHAIN",
                entityId = createdId,
                summary = ctx.getString(R.string.audit_chain_created, nm),
                payload = JSONObject().put("name", nm).put("stepsCount", steps.size),
                undoable = true
            )
        }
        access.persist()
    }

    fun updateChain(chainId: Long, name: String, steps: List<TaskChainStep>) {
        if (access.blockWriteIfNeeded()) return
        liveSnapshot.update { cur ->
            cur.copy(
                chains = cur.chains.map { c ->
                    if (c.id == chainId) c.copy(name = name.trim().ifBlank { c.name }, steps = steps)
                    else c
                }
            )
        }

        val ctx = access.appContextOrNull()
        if (ctx != null) {
            val nm = snapshot().chains.firstOrNull { it.id == chainId }?.name ?: name.trim()
            access.logUserEvent(
                action = "CHAIN_UPDATE",
                entityType = "CHAIN",
                entityId = chainId,
                summary = ctx.getString(R.string.audit_chain_updated, nm.ifBlank { chainId.toString() }),
                payload = JSONObject().put("chainId", chainId).put("name", nm).put("stepsCount", steps.size),
                undoable = true
            )
        }
        access.persist()
    }

    fun deleteChain(chainId: Long) {
        if (access.blockWriteIfNeeded()) return
        liveSnapshot.update { cur ->
            val now = System.currentTimeMillis()
            cur.copy(
                chains = cur.chains.map { c ->
                    if (c.id == chainId) c.copy(isDeleted = true, deletedAtMs = now) else c
                },
                activeChainRun = cur.activeChainRun?.takeIf { it.chainId != chainId }
            )
        }

        val ctx = access.appContextOrNull()
        if (ctx != null) {
            val nm = snapshot().chains.firstOrNull { it.id == chainId }?.name ?: ""
            access.logUserEvent(
                action = "CHAIN_DELETE",
                entityType = "CHAIN",
                entityId = chainId,
                summary = ctx.getString(R.string.audit_chain_deleted, nm.ifBlank { chainId.toString() }),
                payload = JSONObject().put("chainId", chainId).put("name", nm),
                undoable = true
            )
        }
        access.persist()
    }

    fun restoreChain(chainId: Long) {
        if (access.blockWriteIfNeeded()) return
        liveSnapshot.update { cur ->
            cur.copy(
                chains = cur.chains.map { c ->
                    if (c.id == chainId) c.copy(isDeleted = false, deletedAtMs = null) else c
                }
            )
        }

        val ctx = access.appContextOrNull()
        if (ctx != null) {
            val nm = snapshot().chains.firstOrNull { it.id == chainId }?.name ?: ""
            access.logUserEvent(
                action = "CHAIN_RESTORE",
                entityType = "CHAIN",
                entityId = chainId,
                summary = ctx.getString(R.string.audit_chain_restored, nm.ifBlank { chainId.toString() }),
                payload = JSONObject().put("chainId", chainId).put("name", nm),
                undoable = true
            )
        }
        access.persist()
    }

    fun purgeChain(chainId: Long) {
        if (access.blockWriteIfNeeded()) return
        liveSnapshot.update { cur ->
            cur.copy(
                chains = cur.chains.filterNot { it.id == chainId },
                activeChainRun = cur.activeChainRun?.takeIf { it.chainId != chainId }
            )
        }

        access.appContextOrNull()?.let { ctx ->
            access.logUserEvent(
                action = "CHAIN_PURGE",
                entityType = "CHAIN",
                entityId = chainId,
                summary = ctx.getString(R.string.audit_chain_purged, chainId.toString()),
                payload = JSONObject().put("chainId", chainId).put("name", ""),
                undoable = false
            )
        }
        access.persist()
    }

    fun purgeAllDeletedChains() {
        if (access.blockWriteIfNeeded()) return
        val deleted = snapshot().chains.filter { it.isDeleted }
        if (deleted.isEmpty()) return
        val deletedIds = deleted.map { it.id }.toSet()

        liveSnapshot.update { cur ->
            cur.copy(
                chains = cur.chains.filterNot { it.id in deletedIds },
                activeChainRun = cur.activeChainRun?.takeIf { it.chainId !in deletedIds }
            )
        }

        access.appContextOrNull()?.let { ctx ->
            access.logUserEvent(
                action = "CHAIN_PURGE_ALL",
                entityType = "CHAIN",
                entityId = -1L,
                summary = ctx.getString(R.string.audit_chain_purge_all, deleted.size),
                payload = JSONObject().put("count", deleted.size),
                undoable = false
            )
        }
        access.persist()
    }

    fun startChain(chainId: Long) {
        if (access.blockWriteIfNeeded()) return
        access.requireSessionOnlyMode()
        val now = System.currentTimeMillis()
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("start chainId=$chainId") {
            runCatching {
                val cur = snapshot()
                val chain = cur.chains.firstOrNull { it.id == chainId && !it.isDeleted } ?: return@runCatching
                val step0 = chain.steps.firstOrNull() ?: return@runCatching
                val derivedName = deriveStepName(step0, fallback = "Step 1")

                val session = access.sessionCore(ctx)
                val newSessionId = session.insertSession(
                    title = derivedName,
                    startMs = now,
                    endMs = null,
                    tagIds = step0.tagIds
                )

                liveSnapshot.update {
                    it.copy(activeChainRun = ActiveChainRun(chainId = chain.id, stepIndex = 0, currentSessionId = newSessionId))
                }

                access.handleTimeFenceEvents(
                    events = listOf(
                        TimeFenceEvent(
                            trigger = TimeFenceTrigger.ON_START,
                            sessionId = newSessionId,
                            sessionTitle = derivedName,
                            sessionTagIds = step0.tagIds
                        )
                    ),
                    nowMs = now
                )

                access.logUserEvent(
                    action = "CHAIN_START",
                    entityType = "CHAIN",
                    entityId = chainId,
                    summary = ctx.getString(R.string.audit_chain_started, chain.name.ifBlank { chainId.toString() }),
                    payload = JSONObject().put("chainId", chainId).put("name", chain.name),
                    undoable = false
                )

                access.scheduleSessionsRefresh(ctx, now)
                access.scheduleAutoBackup()
                access.persist()
            }.onFailure { err ->
                Log.e("ChainsCapsule", "startChain(session-only) failed", err)
            }
        }
    }

    fun stopChainRun() {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull()
        val runBefore = snapshot().activeChainRun
        val chainId = runBefore?.chainId
        val nm = if (chainId != null) snapshot().chains.firstOrNull { it.id == chainId }?.name ?: "" else ""

        liveSnapshot.update { cur ->
            cur.copy(activeChainRun = null)
        }

        if (ctx != null) {
            access.logUserEvent(
                action = "CHAIN_STOP",
                entityType = "CHAIN",
                entityId = chainId,
                summary = ctx.getString(R.string.audit_chain_stopped, nm.ifBlank { chainId?.toString() ?: "" }),
                payload = JSONObject().put("chainId", chainId).put("name", nm),
                undoable = false
            )
        }
        access.persist()
    }

    suspend fun advanceOnSessionStop(stoppedSessionId: Long, nowMs: Long, context: Context) {
        val run = snapshot().activeChainRun ?: return
        if (run.currentSessionId != stoppedSessionId) return

        val cur = snapshot()
        val chain = cur.chains.firstOrNull { it.id == run.chainId && !it.isDeleted } ?: return

        val nextIndex = run.stepIndex + 1
        val nextStep = chain.steps.getOrNull(nextIndex)
        if (nextStep == null) {
            liveSnapshot.update { it.copy(activeChainRun = null) }
            access.logUserEvent(
                action = "CHAIN_COMPLETE",
                entityType = "CHAIN",
                entityId = chain.id,
                summary = context.getString(R.string.audit_chain_completed, chain.name.ifBlank { chain.id.toString() }),
                payload = JSONObject().put("chainId", chain.id).put("name", chain.name),
                undoable = false
            )
            return
        }

        val derivedName = deriveStepName(nextStep, fallback = "Step ${nextIndex + 1}")
        val session = access.sessionCore(context)
        val newSessionId = session.insertSession(
            title = derivedName,
            startMs = nowMs,
            endMs = null,
            tagIds = nextStep.tagIds
        )

        liveSnapshot.update {
            it.copy(
                activeChainRun = run.copy(stepIndex = nextIndex, currentSessionId = newSessionId)
            )
        }

        access.handleTimeFenceEvents(
            events = listOf(
                TimeFenceEvent(
                    trigger = TimeFenceTrigger.ON_START,
                    sessionId = newSessionId,
                    sessionTitle = derivedName,
                    sessionTagIds = nextStep.tagIds
                )
            ),
            nowMs = nowMs
        )

        access.logUserEvent(
            action = "CHAIN_ADVANCE",
            entityType = "CHAIN",
            entityId = chain.id,
            summary = context.getString(R.string.audit_chain_advanced, chain.name.ifBlank { chain.id.toString() }, nextIndex + 1),
            payload = JSONObject().put("chainId", chain.id).put("stepIndex", nextIndex).put("sessionId", newSessionId),
            undoable = false
        )
    }

    private fun deriveStepName(step: TaskChainStep, fallback: String): String {
        return step.name.trim().ifBlank {
            val tagNames = uiState.value.tags
                .filter { it.id in step.tagIds && !it.isDeleted }
                .map { it.name }
            if (tagNames.isNotEmpty()) tagNames.joinToString(" + ") else fallback
        }
    }

    private fun buildUiState(host: ChainsHostState, snapshot: ChainsSnapshot): ChainsUiState {
        return ChainsUiState(
            tags = host.tags,
            chains = snapshot.chains,
            activeChainRun = snapshot.activeChainRun,
            isReadOnly = host.isReadOnly,
        )
    }
}
