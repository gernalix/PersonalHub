package com.gernalix.luoghi.capsules.geofence

import com.google.android.gms.location.Geofence

enum class PlaceGeofenceAction {
    NOTIFY,
    AUTOMATIC_VISIT;

    companion object {
        fun parse(value: String?): PlaceGeofenceAction =
            entries.firstOrNull { it.name == value } ?: NOTIFY
    }
}

enum class PlaceGeofenceTransition(val storageName: String, val geofenceTransition: Int) {
    ENTER("ENTER", Geofence.GEOFENCE_TRANSITION_ENTER),
    EXIT("EXIT", Geofence.GEOFENCE_TRANSITION_EXIT);

    companion object {
        fun fromGeofenceTransition(value: Int): PlaceGeofenceTransition? =
            entries.firstOrNull { it.geofenceTransition == value }
    }
}

sealed interface PlaceGeofenceResult {
    data object Success : PlaceGeofenceResult
    data object PermissionMissing : PlaceGeofenceResult
    data object PlaceMissing : PlaceGeofenceResult
    data object CoordinatesMissing : PlaceGeofenceResult
    data object AmbiguousTransition : PlaceGeofenceResult
    data object DuplicateTransition : PlaceGeofenceResult
    data class VisitRejected(val reason: String) : PlaceGeofenceResult
}
