// v471
package com.example.multitimetracker.util

import android.util.Log
import com.example.multitimetracker.BuildConfig

/**
 * CapsuleAudit: lightweight runtime warning layer to keep Feature Capsule boundaries visible.
 *
 * NOTE:
 * - This does NOT enforce boundaries at compile-time.
 * - It provides:
 *   (A) a single place to track known capsule violations
 *   (B) a small API to emit runtime warnings when a cross-capsule write happens
 *
 * This is intentionally tiny and dependency-free (Logcat only).
 */
object CapsuleAudit {

    private const val TAG = "CapsuleAudit"

    // --- Runtime self-check (v219) ---
    private const val MAX_RECENT_BYPASS = 50
    private val recentWriteBypass = ArrayDeque<String>(MAX_RECENT_BYPASS)

    /**
     * Called from persistence write-paths to detect suspicious call sites that bypass the intended capsule/bridge.
     *
     * Heuristic:
     * - Find the first stack frame inside our app package that is NOT in persistence/util/core/model/contracts.
     * - If it's not in MainViewModel or any capsule package, log a warning and record it for Diagnostics.
     */
    fun auditPersistenceWrite(what: String) {
        if (!BuildConfig.DEBUG) return
        val appPkg = "com.example.multitimetracker"
        val allowedHints = listOf(".MainViewModel", ".capsules.")
        val ignoredHints = listOf(".persistence.", ".util.", ".model.", ".core.", ".ui.theme.", ".ui.components.")
        val st = Throwable().stackTrace

        val offender = st.firstOrNull { el ->
            val cn = el.className ?: return@firstOrNull false
            cn.startsWith(appPkg) &&
                ignoredHints.none { cn.contains(it) }
        }

        val offenderClass = offender?.className ?: "<unknown>"
        val offenderMethod = offender?.methodName ?: "<unknown>"
        val offenderLine = offender?.lineNumber ?: -1

        val allowed = allowedHints.any { offenderClass.contains(it) }
        if (allowed) return

        val msg = "WRITE_BYPASS | $what | caller=$offenderClass#$offenderMethod:$offenderLine"
        Log.w(TAG, msg)
        if (recentWriteBypass.size >= MAX_RECENT_BYPASS) {
            recentWriteBypass.removeFirst()
        }
        recentWriteBypass.addLast(msg)
    }

    fun dumpRecentWriteBypass(): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        return recentWriteBypass.toList()
    }


    data class Violation(
        val id: String,
        val fromCapsule: String,
        val toCapsule: String,
        val why: String,
        val whereHint: String,
    )

    // Keep this list small and high-signal. Add only when we *know* it's a real boundary leak.
    // v212: CAP-001 fixed by moving ClosedSessionRecord/TaggedSessionRecord into core/contracts.
    val knownViolations: List<Violation> = emptyList()

    /**
     * Call once at startup (debug only) to make boundary leaks visible in Logcat.
     */
    fun logKnownViolations() {
        if (!BuildConfig.DEBUG) return
        if (knownViolations.isEmpty()) return

        Log.w(TAG, "Known capsule violations: ${knownViolations.size}")
        for (v in knownViolations) {
            Log.w(
                TAG,
                "[${v.id}] ${v.fromCapsule} -> ${v.toCapsule} | ${v.why} | where=${v.whereHint}"
            )
        }
    }

    /**
     * Optional: use this on cross-capsule write/read paths to keep them explicit during refactors.
     */
    fun warn(fromCapsule: String, toCapsule: String, what: String, where: String? = null) {
        if (!BuildConfig.DEBUG) return
        val suffix = if (where.isNullOrBlank()) "" else " | where=$where"
        Log.w(TAG, "CROSS-CAPSULE: $fromCapsule -> $toCapsule | $what$suffix")
    }

    /**
     * Overload used by capsule-specific helpers.
     *
     * Keep the signature stable so call sites can use named parameters like:
     * `warn(code = ..., originCapsule = ..., detail = ...)`.
     */
    fun warn(code: String, originCapsule: String, detail: String, targetCapsule: String = "TAGS", where: String? = null) {
        warn(
            fromCapsule = originCapsule,
            toCapsule = targetCapsule,
            what = "$code | $detail",
            where = where,
        )
    }

    // --- Tags Isolation (v141) ---
    const val TAG_CROSS_WRITE = "TAG_CROSS_WRITE"

    /**
     * Tags capsule boundary warning:
     * Use this when a non-Tags capsule is about to mutate tag ownership data.
     */
    fun warnTagCrossWrite(originCapsule: String, detail: String) {
        warn(code = TAG_CROSS_WRITE, originCapsule = originCapsule, detail = detail)
    }

}

