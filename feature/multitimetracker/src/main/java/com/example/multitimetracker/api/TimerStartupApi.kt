package com.example.multitimetracker.api

import android.content.Context
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.persistence.LegacyTagSessionRepair

/** Public host entrypoint for Timer startup work. */
object TimerStartupApi {
    fun applicationOnCreate() = StartupPerfTrace.applicationOnCreate()

    /**
     * Heals tag/session edges omitted by older session-only bootstraps.
     *
     * The host calls this after interrupted DB-import recovery and before any
     * Activity can mutate Timer state. The repair is additive/idempotent and
     * CriticalDataGuard remains authoritative if a legacy row cannot be mapped.
     */
    fun repairLegacyTagSessionsAfterHostDatabaseRecovery(context: Context) {
        LegacyTagSessionRepair.repairIfNeeded(context.applicationContext)
    }
}
