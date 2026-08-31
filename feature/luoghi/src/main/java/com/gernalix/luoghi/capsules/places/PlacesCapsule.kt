package com.gernalix.luoghi.capsules.places

import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceRepository
import kotlinx.coroutines.flow.Flow

data class PlaceMutation(
    val uuid: String?,
    val nickname: String,
    val address: String?,
    val lat: Double?,
    val lon: Double?,
    val radiusM: Double?,
    val notes: String?,
    val sourceApp: String = "Luoghi",
)

class PlacesCapsule(private val repository: PlaceRepository) {
    val places: Flow<List<PlaceEntity>> = repository.places

    suspend fun save(mutation: PlaceMutation): String =
        repository.savePlace(
            uuid = mutation.uuid,
            nickname = mutation.nickname,
            address = mutation.address,
            lat = mutation.lat,
            lon = mutation.lon,
            radiusM = mutation.radiusM,
            notes = mutation.notes,
            sourceApp = mutation.sourceApp,
        )

    suspend fun delete(uuid: String) {
        repository.deletePlace(uuid)
    }

    suspend fun archive(uuid: String) {
        repository.archivePlace(uuid, archived = true)
    }
}
