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
            return MapMarkerModel(
                uuid = place.uuid,
                title = label?.takeIf { it.isNotBlank() } ?: place.nickname.ifBlank { place.uuid.take(8) },
                address = place.address,
                notes = place.notes,
                latitude = lat,
                longitude = lon,
            )
        }
    }
}

data class MapOverlayMarker(
    val uuid: String?,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val snippet: String?,
    val count: Int = 1,
)

data class MapViewerModel(
    val title: String,
    val callerMode: MapCallerMode,
    val markerMode: MapMarkerMode,
    val markers: List<MapMarkerModel>,
    val missingCoordinateCount: Int,
    val currentLocation: MapCurrentLocation? = null,
) {
    val overlays: List<MapOverlayMarker> = MapMarkerClusterer.overlays(markers)
    val usesPerformanceClusterFallback: Boolean = markers.size > MapMarkerClusterer.MAX_DIRECT_MARKERS
}

data class MapCurrentLocation(
    val latitude: Double,
    val longitude: Double,
)

object MapMarkerClusterer {
    const val MAX_DIRECT_MARKERS = 500
    private const val CLUSTER_CELL_DEGREES = 0.02

    fun overlays(markers: List<MapMarkerModel>): List<MapOverlayMarker> {
        if (markers.size <= MAX_DIRECT_MARKERS) {
            return markers.map { marker ->
                MapOverlayMarker(
                    uuid = marker.uuid,
                    latitude = marker.latitude,
                    longitude = marker.longitude,
                    title = marker.title,
                    snippet = listOfNotNull(marker.address, marker.notes).joinToString("\n").takeIf { it.isNotBlank() },
                )
            }
        }
        return markers
            .groupBy { marker ->
                Pair(
                    (marker.latitude / CLUSTER_CELL_DEGREES).toInt(),
                    (marker.longitude / CLUSTER_CELL_DEGREES).toInt(),
                )
            }
            .values
            .map { bucket ->
                if (bucket.size == 1) {
                    val marker = bucket.single()
                    MapOverlayMarker(
                        uuid = marker.uuid,
                        latitude = marker.latitude,
                        longitude = marker.longitude,
                        title = marker.title,
                        snippet = marker.address,
                    )
                } else {
                    MapOverlayMarker(
                        uuid = null,
                        latitude = bucket.map { it.latitude }.average(),
                        longitude = bucket.map { it.longitude }.average(),
                        title = "${bucket.size} places",
                        snippet = bucket.take(5).joinToString("\n") { it.title },
                        count = bucket.size,
                    )
                }
            }
    }
}
