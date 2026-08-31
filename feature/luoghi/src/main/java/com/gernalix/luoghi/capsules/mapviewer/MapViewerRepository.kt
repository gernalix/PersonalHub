package com.gernalix.luoghi.capsules.mapviewer

import com.gernalix.luoghi.data.PlaceDao
import com.gernalix.luoghi.data.PlaceEntity

class MapViewerRepository(private val dao: PlaceDao) {
    fun load(
        title: String,
        callerMode: MapCallerMode,
        markerMode: MapMarkerMode,
        uuids: List<String>,
        labels: List<String>,
    ): MapViewerModel {
        val places = if (uuids.isEmpty()) {
            dao.placesWithCoordinatesBlocking(MAX_MAP_PLACES)
        } else {
            val byUuid = dao.placesByUuidsBlocking(uuids).associateBy { it.uuid }
            uuids.mapNotNull(byUuid::get)
        }
        val labelsByUuid = uuids.mapIndexedNotNull { index, uuid ->
            labels.getOrNull(index)?.let { uuid to it }
        }.toMap()
        val markers = places.mapNotNull { place -> MapMarkerModel.from(place, labelsByUuid[place.uuid]) }
        val missing = places.count { it.lat == null || it.lon == null }
        return MapViewerModel(
            title = title,
            callerMode = callerMode,
            markerMode = markerMode,
            markers = markers,
            missingCoordinateCount = missing,
        )
    }

    private companion object {
        const val MAX_MAP_PLACES = 20_000
    }
}
