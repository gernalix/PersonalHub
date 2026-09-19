package com.gernalix.luoghi.hub

import android.content.Context
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceRegistrar
import com.gernalix.luoghi.data.LuoghiDatabase

/** Host boundary for replacing platform geofences when the canonical profile changes. */
object PlacesProfileRuntime {
    suspend fun retireActiveProfile(context: Context) {
        val app = context.applicationContext
        PlaceGeofenceRegistrar(app, LuoghiDatabase.get(app).placeDao()).removeGeofences()
    }

    suspend fun restoreActiveProfile(context: Context) {
        val app = context.applicationContext
        PlaceGeofenceRegistrar(app, LuoghiDatabase.get(app).placeDao()).reconcile()
    }
}
