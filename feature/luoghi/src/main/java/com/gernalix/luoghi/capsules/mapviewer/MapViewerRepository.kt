package com.gernalix.luoghi.capsules.mapviewer

import com.gernalix.luoghi.data.PlaceDao
import com.gernalix.luoghi.data.PlaceEntity

class MapViewerRepository(private val dao: PlaceDao, private val unnamedLabel: String = "Unnamed place") {
    fun load(
        title: String,
        callerMode: MapCallerMode,
        markerMode: MapMarkerMode,
        uuids: List<String>,
        labels: List<String>,
        currentLocation: MapCurrentLocation? = null,
    ): MapViewerModel {
        val places = if (uuids.isEmpty()) {
            dao.listPlacesBlocking()
        } else {
            val byUuid = uuids.distinct().chunked(900).flatMap(dao::placesByUuidsBlocking).associateBy { it.uuid }
            uuids.mapNotNull(byUuid::get)
        }
        val labelsByUuid = uuids.mapIndexedNotNull { index, uuid ->
            labels.getOrNull(index)?.let { uuid to it }
        }.toMap()
        val markers = places.mapNotNull { place -> MapMarkerModel.from(place, labelsByUuid[place.uuid], unnamedLabel) }
        val missing = places.count { it.lat == null || it.lon == null }
        return MapViewerModel(
            title = title,
            callerMode = callerMode,
            markerMode = markerMode,
            markers = markers,
            missingCoordinateCount = missing,
            currentLocation = currentLocation,
        )
    }

}
