package com.example.multitimetracker.capsules.system

import android.content.Context

enum class CapsuleRuntimeChange {
    SNAPSHOT_RELOAD,
    POST_IMPORT,
    RECOVERY_RESTORE,
    VAULT_CHANGED,
    TIME_MACHINE_CHANGED,
}

interface CapsuleRuntimeParticipant {
    val capsuleId: String

    fun refreshAfterSnapshot(context: Context) {
        onCapsuleRuntimeChanged(context, CapsuleRuntimeChange.SNAPSHOT_RELOAD)
    }

    fun refreshAfterImport(context: Context) {
        onCapsuleRuntimeChanged(context, CapsuleRuntimeChange.POST_IMPORT)
    }

    fun recoverAfterRestore(context: Context) {
        onCapsuleRuntimeChanged(context, CapsuleRuntimeChange.RECOVERY_RESTORE)
    }

    fun refreshForVaultChange(context: Context?) {
        onCapsuleRuntimeChanged(context, CapsuleRuntimeChange.VAULT_CHANGED)
    }

    fun refreshForTimeMachineChange(context: Context?) {
        onCapsuleRuntimeChanged(context, CapsuleRuntimeChange.TIME_MACHINE_CHANGED)
    }

    fun onCapsuleRuntimeChanged(context: Context?, change: CapsuleRuntimeChange)
}
