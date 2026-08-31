package com.example.multitimetracker.capsules.system

import android.content.Context
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.core.session.SessionCore
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

interface SessionOwnerCapsuleAccess {
    fun runtimeScope(): CoroutineScope
    fun tags(): List<Tag>

    fun appContextOrNull(): Context?
    fun blockWriteIfNeeded(): Boolean
    fun requireSessionOnlyMode()
    fun sessionCore(context: Context): SessionCore
    fun launchIo(reason: String, block: suspend () -> Unit)

    fun handleTimeFenceEvents(events: List<TimeFenceEvent>, nowMs: Long)
    suspend fun advanceChainOnSessionStop(stoppedSessionId: Long, nowMs: Long, context: Context)

    fun logUserEvent(action: String, entityType: String?, entityId: Long?, summary: String, payload: JSONObject?, undoable: Boolean)
    fun logSystemEvent(action: String, entityType: String?, entityId: Long?, summary: String, payload: JSONObject?)
    fun scheduleSessionsRefresh(context: Context, nowMs: Long)
    fun scheduleAutoBackup()
    fun persist()

    fun showSessionWriteFailed(context: Context)
    fun showSessionDeleteFailed(context: Context)
    fun onCreatedOnMain(created: SessionUi, callback: (SessionUi) -> Unit)
}
