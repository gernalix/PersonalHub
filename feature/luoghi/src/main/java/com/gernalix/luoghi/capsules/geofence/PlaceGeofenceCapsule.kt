package com.gernalix.luoghi.capsules.geofence

import android.content.Context
import com.gernalix.luoghi.data.PlaceGeofenceConfigEntity
import com.gernalix.luoghi.data.PlaceRepository
import kotlinx.coroutines.flow.Flow

class PlaceGeofenceCapsule(
    private val context: Context,
    private val repository: PlaceRepository,
) {
    val configs: Flow<List<PlaceGeofenceConfigEntity>> = repository.geofenceConfigs

    suspend fun save(config: PlaceGeofenceConfigEntity): PlaceGeofenceResult {
        repository.saveGeofenceConfig(config)
        return PlaceGeofenceRegistrar(
            context.applicationContext,
            com.gernalix.luoghi.data.LuoghiDatabase.get(context.applicationContext).placeDao(),
        ).reconcile()
    }
}
