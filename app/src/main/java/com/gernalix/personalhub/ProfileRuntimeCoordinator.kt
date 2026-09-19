package com.gernalix.personalhub

import android.content.Context
import com.example.multitimetracker.api.TimerProfileRuntime
import com.gernalix.luoghi.hub.PlacesProfileRuntime

object ProfileRuntimeCoordinator {
    suspend fun retireActiveProfile(context: Context) {
        TimerProfileRuntime.retireActiveProfile(context)
        PlacesProfileRuntime.retireActiveProfile(context)
    }

    suspend fun restoreActiveProfile(context: Context) {
        TimerProfileRuntime.restoreActiveProfile(context)
        PlacesProfileRuntime.restoreActiveProfile(context)
    }
}
