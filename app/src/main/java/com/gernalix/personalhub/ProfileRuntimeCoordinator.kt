package com.gernalix.personalhub

import android.content.Context
import com.example.multitimetracker.api.TimerProfileRuntime
import com.gernalix.luoghi.hub.PlacesProfileRuntime
import com.gernalix.personalhub.core.database.DatabaseProfiles

object ProfileRuntimeCoordinator {
    suspend fun retireActiveProfile(context: Context) {
        TimerProfileRuntime.retireActiveProfile(context)
        PlacesProfileRuntime.retireActiveProfile(context)
    }

    suspend fun restoreActiveProfile(context: Context) {
        TimerProfileRuntime.restoreActiveProfile(context)
        PlacesProfileRuntime.restoreActiveProfile(context)
    }

    suspend fun switchActiveProfile(context: Context, profileId: String): Boolean =
        retireSwitchRestore(
            retire = { retireActiveProfile(context) },
            switch = { DatabaseProfiles.switch(context, profileId) },
            restore = { restoreActiveProfile(context) },
        )

    internal suspend fun <T> retireSwitchRestore(
        retire: suspend () -> Unit,
        switch: suspend () -> T,
        restore: suspend () -> Unit,
    ): T {
        try {
            retire()
        } catch (retireFailure: Throwable) {
            try {
                restore()
            } catch (restoreFailure: Throwable) {
                retireFailure.addSuppressed(restoreFailure)
            }
            throw retireFailure
        }

        val value = try {
            switch()
        } catch (switchFailure: Throwable) {
            try {
                restore()
            } catch (restoreFailure: Throwable) {
                switchFailure.addSuppressed(restoreFailure)
            }
            throw switchFailure
        }

        restore()
        return value
    }
}
