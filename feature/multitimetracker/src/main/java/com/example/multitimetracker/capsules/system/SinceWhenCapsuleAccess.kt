package com.example.multitimetracker.capsules.system

import com.example.multitimetracker.capsules.sincewhen.state.SinceWhenHostState

/**
 * SINCE_WHEN capsule access contract.
 */
interface SinceWhenCapsuleAccess {
    fun hostState(): SinceWhenHostState
    fun observeHostState(onChanged: (SinceWhenHostState) -> Unit)
    fun blockWriteIfNeeded(): Boolean
    fun touchNow()
    fun persist()
    fun scheduleAutoBackup()
}
