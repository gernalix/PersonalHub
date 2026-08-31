// v445
package com.example.multitimetracker.util

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * v445: relaunch the launcher task after a database switch.
 *
 * Why we do it this way:
 * - v438 used AlarmManager.setExact(), which can throw SecurityException on modern Android
 *   when the app does not have exact-alarm privileges.
 * - In this app, a clean MainActivity relaunch is enough: ViewModels are recreated and
 *   SQLite helpers are opened fresh against the newly switched internal DB file.
 * - v445: the old Activity can still receive ON_PAUSE after the DB import but before process
 *   teardown. We must skip that one background persist, otherwise stale in-memory state from the
 *   previous vault can overwrite the freshly imported DB.
 */
object AppRestarter {
    private const val RESTART_GUARD_MS = 15_000L

    @Volatile
    private var skipBackgroundPersistUntilElapsedMs: Long = 0L

    fun prepareForRestart() {
        skipBackgroundPersistUntilElapsedMs = SystemClock.elapsedRealtime() + RESTART_GUARD_MS
    }

    fun isRestartPending(): Boolean {
        val deadline = skipBackgroundPersistUntilElapsedMs
        if (deadline == 0L) return false
        if (SystemClock.elapsedRealtime() > deadline) {
            skipBackgroundPersistUntilElapsedMs = 0L
            return false
        }
        return true
    }

    fun consumeBackgroundPersistSkip(): Boolean {
        return isRestartPending()
    }

    fun cancelPendingRestart() {
        skipBackgroundPersistUntilElapsedMs = 0L
    }

    fun restart(context: Context): Boolean {
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        prepareForRestart()

        return runCatching {
            app.startActivity(launch)
        }.onFailure {
            cancelPendingRestart()
        }.isSuccess
    }
}
