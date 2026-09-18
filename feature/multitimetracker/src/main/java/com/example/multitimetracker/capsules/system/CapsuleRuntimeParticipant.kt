package com.example.multitimetracker.capsules.system

import android.content.Context

enum class CapsuleRuntimeChange {
    SNAPSHOT_RELOAD,
}

interface CapsuleRuntimeParticipant {
    val capsuleId: String

    fun refreshAfterSnapshot(context: Context) {
        onCapsuleRuntimeChanged(context, CapsuleRuntimeChange.SNAPSHOT_RELOAD)
    }

    fun onCapsuleRuntimeChanged(context: Context?, change: CapsuleRuntimeChange)
}
