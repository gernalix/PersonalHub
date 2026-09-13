package com.example.multitimetracker

import android.content.Context
import com.example.multitimetracker.model.SessionUi

object RandomTimerStore {
    private const val PREFS = "random_timer_runs"
    private const val KEY_IDS = "ids"
    private const val KEY_TARGET_PREFIX = "target_"
    private const val KEY_ANSWERED_PREFIX = "answered_"

    fun saveRun(context: Context, sessionId: Long, targetMinutes: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty() + sessionId.toString()
        prefs.edit()
            .putStringSet(KEY_IDS, ids)
            .putInt(KEY_TARGET_PREFIX + sessionId, targetMinutes.coerceAtLeast(1))
            .putBoolean(KEY_ANSWERED_PREFIX + sessionId, false)
            .apply()
    }

    fun isRandomSession(context: Context, sessionId: Long): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet()).orEmpty()
            .contains(sessionId.toString())

    fun targetMinutes(context: Context, sessionId: Long): Int? {
        if (!isRandomSession(context, sessionId)) return null
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TARGET_PREFIX + sessionId, -1)
        return value.takeIf { it > 0 }
    }

    fun unansweredCompletedSession(context: Context, sessions: List<SessionUi>): SessionUi? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val randomIds = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
        return sessions.firstOrNull { session ->
            session.endMs != null &&
                session.deletedAtMs == null &&
                session.id.toString() in randomIds &&
                !prefs.getBoolean(KEY_ANSWERED_PREFIX + session.id, false)
        }
    }

    fun markAnswered(context: Context, sessionId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ANSWERED_PREFIX + sessionId, true)
            .apply()
    }
}
