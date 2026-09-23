package com.example.multitimetracker.capsules.system

import android.content.Context
import com.example.multitimetracker.capsules.quickevents.state.QuickEventsHostState
import com.example.multitimetracker.core.quickevent.QuickEventCore
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.capsules.quickevents.public.QuickEventsSnapshot
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

interface QuickEventsCapsuleAccess {
    fun hostStateFlow(): StateFlow<QuickEventsHostState>
    fun appContextOrNull(): Context?
    fun blockWriteIfNeeded(): Boolean
    fun quickEventCore(context: Context): QuickEventCore
    fun launchIo(reason: String, block: suspend () -> Unit)
    fun logUserEvent(action: String, entityType: String?, entityId: Long?, summary: String, payload: JSONObject?, undoable: Boolean)
    fun persist()
    fun scheduleAutoBackup()
    fun showWriteFailed(context: Context)
    fun showDeleteFailed(context: Context)
    fun showTargetRecorded(context: Context, title: String)
    fun addTag(name: String)
    fun syncSharedTagAssignments(snapshot: QuickEventsSnapshot)
}
