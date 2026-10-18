package com.gernalix.luoghi.capsules.mapviewer

import com.gernalix.luoghi.data.PlaceEntity

enum class MapCallerMode {
    SOLDI,
    MTT,
    SC,
    GLOBAL,
    UNKNOWN;

    companion object {
        fun parse(value: String?): MapCallerMode =
            entries.firstOrNull { it.name.equals(value.orEmpty(), ignoreCase = true) } ?: UNKNOWN
    }
}

enum class MapMarkerMode {
    SIMPLE,
    COMPACT_LABEL,
    BALLOON_ON_TAP,
    BALLOON_ALL;

    companion object {
        fun parse(value: String?): MapMarkerMode =
            entries.firstOrNull { it.name.equals(value.orEmpty(), ignoreCase = true) } ?: SIMPLE
    }
}

data class MapMarkerModel(
    val uuid: String,
    val title: String,
    val address: String?,
    val notes: String?,
    val latitude: Double,
    val longitude: Double,
) {
    companion object {
        fun from(place: PlaceEntity, label: String?): MapMarkerModel? {
            val lat = place.lat ?: return null
            val lon = place.lon ?: return null
            if (!OsmAndMapPlan.valid(lat, lon)) return null
            return MapMarkerModel(
                uuid = place.uuid,
                title = label?.takeIf { it.isNotBlank() } ?: place.nickname.ifBlank { "Unnamed place" },
                address = place.address,
                notes = place.notes,
                latitude = lat,
                longitude = lon,
            )
        }
    }
}

data class MapViewerModel(
    val title: String,
    val callerMode: MapCallerMode,
    val markerMode: MapMarkerMode,
    val markers: List<MapMarkerModel>,
    val missingCoordinateCount: Int,
    val currentLocation: MapCurrentLocation? = null,
)

data class MapCurrentLocation(
    val latitude: Double,
    val longitude: Double,
)
