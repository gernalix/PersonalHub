// v461
package com.example.multitimetracker.capsules.now.controller

import android.content.Context
import android.net.Uri
import com.example.multitimetracker.capsules.sessions.public.SessionOwnerPublicApi
import com.example.multitimetracker.capsules.now.state.NowUiState
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.capsules.system.NowCapsuleAccess
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * ===== FEATURE CAPSULE: Now.SessionList (ViewModel) — START =====
 *
 * Scope:
 * - Pure mapping/helpers for the NOW tab session-only list.
 * - No Android ViewModel lifecycle here on purpose: the app already centralizes state in MainViewModel.
 *   This capsule is the *ViewModel block* of the feature capsule pattern: it owns the presentation logic
 *   that would otherwise be scattered in the screen composable.
 */
class NowCapsuleViewModel(
    private val access: NowCapsuleAccess,
    private val sessionOwner: SessionOwnerPublicApi? = null,
    private val updateSessionOverride: ((Long, String, Set<Long>) -> Unit)? = null,
    private val updateSessionTimesOverride: ((Long, Long, Long?) -> Unit)? = null,
    private val deleteSessionOverride: ((Long) -> Unit)? = null,
    private val createNewSessionOverride: ((String, Long, Set<Long>, (SessionUi) -> Unit) -> Unit)? = null,
    private val randomTargetMinute: (Int) -> Int = { maxMinutes -> Random.nextInt(1, maxMinutes + 1) },
) : CapsuleRuntimeParticipant {
    override val capsuleId: String = "now"
    val uiState: StateFlow<NowUiState> = access.uiStateFlow()

    override fun onCapsuleRuntimeChanged(context: Context?, change: CapsuleRuntimeChange) = Unit

    fun updateSession(sessionId: Long, title: String, tagIds: Set<Long>) {
        sessionOwner?.updateChronologySession(sessionId, title, tagIds)
            ?: updateSessionOverride?.invoke(sessionId, title, tagIds)
            ?: error("NowCapsuleViewModel requires a session owner boundary")
    }

    fun updateSessionTimes(sessionId: Long, startMs: Long, endMsOrNull: Long?) {
        sessionOwner?.updateChronologySessionTimes(sessionId, startMs, endMsOrNull)
            ?: updateSessionTimesOverride?.invoke(sessionId, startMs, endMsOrNull)
            ?: error("NowCapsuleViewModel requires a session owner boundary")
    }

    fun deleteSession(sessionId: Long) {
        sessionOwner?.deleteChronologySession(sessionId)
            ?: deleteSessionOverride?.invoke(sessionId)
            ?: error("NowCapsuleViewModel requires a session owner boundary")
    }

    fun createNewSession(title: String, startMs: Long, tagIds: Set<Long>, onCreated: (SessionUi) -> Unit = {}) {
        sessionOwner?.createRunningSession(title, startMs, tagIds, onCreated)
            ?: createNewSessionOverride?.invoke(title, startMs, tagIds, onCreated)
            ?: error("NowCapsuleViewModel requires a session owner boundary")
    }

    fun createRandomTimer(maxMinutes: Int, startMs: Long, onCreated: (SessionUi) -> Unit = {}) {
        val cleanMax = maxMinutes.coerceAtLeast(1)
        val targetMinutes = randomTargetMinute(cleanMax).coerceIn(1, cleanMax)
        sessionOwner?.createRandomTimerSession(startMs, targetMinutes, onCreated)
            ?: error("NowCapsuleViewModel requires a session owner boundary")
    }

    fun addTag(name: String) {
        access.addTag(name)
    }

    fun exportBackup(context: Context) {
        access.exportBackup(context)
    }

    fun importDbFromUri(context: Context, uri: Uri) {
        access.importDbFromUri(context, uri)
    }

    fun setBackupRootFolder(context: Context, uri: Uri) {
        access.setBackupRootFolder(context, uri)
    }

    companion object {

    /**
     * Active tags (NOW): tags currently present in running sessions.
     * Order deterministically by recency (most recently used first).
     */
    fun computeActiveTags(
        visibleTags: List<Tag>,
        runningMinStartByTagId: Map<Long, Long>,
        tagLastUsedMsByTagId: Map<Long, Long>
    ): List<Tag> {
        return visibleTags
            .asSequence()
            .filter { runningMinStartByTagId.containsKey(it.id) }
            .sortedWith(
                compareByDescending<Tag> {
                    // Prefer global tag recency; fall back to earliest running start if missing.
                    tagLastUsedMsByTagId[it.id] ?: (runningMinStartByTagId[it.id] ?: 0L)
                }.thenBy { it.name.lowercase() }
            )
            .toList()
    }

    /**
     * Active tag shown time = closed-union total + live delta from the earliest running start.
     */
    fun computeActiveTagShownMsByTagId(
        activeTags: List<Tag>,
        activeTagTotalsMsByTagId: Map<Long, Long>,
        runningMinStartByTagId: Map<Long, Long>,
        nowMs: Long
    ): Map<Long, Long> {
        val out = HashMap<Long, Long>(activeTags.size)
        for (t in activeTags) {
            val closed = activeTagTotalsMsByTagId[t.id] ?: 0L
            val minStart = runningMinStartByTagId[t.id]
            val live = if (minStart != null && nowMs >= minStart) (nowMs - minStart) else 0L
            out[t.id] = closed + live
        }
        return out
    }

    /**
     * How many running sessions currently include each tag.
     */
    fun computeActiveCountByTagId(runningSessions: List<SessionUi>): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (s in runningSessions) {
            for (tid in s.tagIds) out[tid] = (out[tid] ?: 0) + 1
        }
        return out
    }

    /**
     * Display title for a running session:
     * - if title present -> use it
     * - else -> join tag names ("tag1 · tag2")
     * - else -> fallback string
     *
     * Fast path: avoids scanning the whole tags list per session.
     */
    fun displayTitle(
        session: SessionUi,
        tagNameById: Map<Long, String>,
        fallbackNoTitle: String
    ): String {
        val t = session.title.trim()
        if (t.isNotEmpty()) return t

        val names = session.tagIds.asSequence()
            .mapNotNull { tagNameById[it] }
            .toList()
        return if (names.isNotEmpty()) names.joinToString(" · ") else fallbackNoTitle
    }

    fun tagNames(session: SessionUi, tagNameById: Map<Long, String>): List<String> {
        return session.tagIds.asSequence()
            .mapNotNull { tagNameById[it] }
            .toList()
    }

    fun runningDurationMs(nowMs: Long, session: SessionUi): Long {
        return (nowMs - session.startMs).coerceAtLeast(0L)
    }
    }
}

// ===== FEATURE CAPSULE: Now.SessionList (ViewModel) — END =====
