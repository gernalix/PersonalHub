package com.gernalix.luoghi.capsules.places

import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceDeleteResult
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
    val tagNames: Set<String> = emptySet(),
    val sourceApp: String = "Luoghi",
)

class PlacesCapsule(private val repository: PlaceRepository) {
    val places: Flow<List<PlaceEntity>> = repository.places
    val tags = repository.placeTags

    suspend fun tagsForPlace(placeUuid: String) = repository.tagsForPlace(placeUuid)
    suspend fun listTags() = repository.listPlaceTags()

    suspend fun save(mutation: PlaceMutation): String {
        val uuid = repository.savePlace(
            uuid = mutation.uuid,
            nickname = mutation.nickname,
            address = mutation.address,
            lat = mutation.lat,
            lon = mutation.lon,
            radiusM = mutation.radiusM,
            notes = mutation.notes,
            sourceApp = mutation.sourceApp,
        )
        repository.setPlaceTags(uuid, mutation.tagNames)
        return uuid
    }

    suspend fun delete(uuid: String): PlaceDeleteResult = repository.deletePlace(uuid)

    suspend fun archive(uuid: String) {
        repository.archivePlace(uuid, archived = true)
    }
}
