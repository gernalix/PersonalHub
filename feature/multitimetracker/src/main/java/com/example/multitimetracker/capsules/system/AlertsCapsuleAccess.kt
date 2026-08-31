// v470
package com.example.multitimetracker.capsules.system

import android.content.Context
import com.example.multitimetracker.capsules.alerts.state.AlertsHostState
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow

/**
 * FCS boundary contract for Alerts capsule.
 *
 * The capsule must NOT access MainViewModel state directly.
 * MainViewModel (or another owner) provides these hooks.
 */
interface AlertsCapsuleAccess {
    fun hostStateFlow(): StateFlow<AlertsHostState>
    fun appContextOrNull(): Context?
    fun resolveSessionStartAtMs(sessionId: Long, nowMs: Long): Long?
    fun tags(): List<Tag>
    fun runningSessions(): List<SessionUi>

    fun persist()
    fun persistAsync()
    fun scheduleAutoBackup()

    fun logUserEvent(action: String, entityType: String, entityId: Long, summary: String, payload: JSONObject?, undoable: Boolean)
    fun logSystemEvent(action: String, entityType: String?, entityId: Long?, summary: String, payload: JSONObject?)
}
