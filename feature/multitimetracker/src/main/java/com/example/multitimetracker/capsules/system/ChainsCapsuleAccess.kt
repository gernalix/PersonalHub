// v336
package com.example.multitimetracker.capsules.system

import android.content.Context
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.capsules.chains.state.ChainsHostState
import com.example.multitimetracker.core.session.SessionCore
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Chains capsule access contract.
 */
interface ChainsCapsuleAccess {
    fun hostStateFlow(): StateFlow<ChainsHostState>
    fun appContextOrNull(): Context?
    fun blockWriteIfNeeded(): Boolean
    fun requireSessionOnlyMode()
    fun sessionCore(context: Context): SessionCore
    fun launchIo(reason: String, block: suspend () -> Unit)
    fun handleTimeFenceEvents(events: List<TimeFenceEvent>, nowMs: Long)
    fun logUserEvent(action: String, entityType: String?, entityId: Long?, summary: String, payload: JSONObject?, undoable: Boolean)
    fun persist()
    fun scheduleAutoBackup()
    fun scheduleSessionsRefresh(context: Context, nowMs: Long)

}
