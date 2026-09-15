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
     * The host runs this off the UI thread after interrupted DB-import recovery.
     * The repair is additive/idempotent and uses a database transaction, so it
     * does not need to delay the first Activity/frame. CriticalDataGuard remains
     * authoritative if a legacy row cannot be mapped.
     */
    fun repairLegacyTagSessionsAfterHostDatabaseRecovery(context: Context) {
        LegacyTagSessionRepair.repairIfNeeded(context.applicationContext)
    }
}
