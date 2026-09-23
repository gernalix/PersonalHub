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
    val photoUri: String? = null,
    val sourceApp: String = "Luoghi",
    val tagNames: Set<String> = emptySet(),
)

class PlacesCapsule(private val repository: PlaceRepository) {
    val places: Flow<List<PlaceEntity>> = repository.places
    val tags = repository.placeTags
    val tagAssignments = repository.placeTagAssignments

    suspend fun tagsForPlace(placeUuid: String) = repository.tagsForPlace(placeUuid)
    suspend fun listTags() = repository.listPlaceTags()
    suspend fun bulkAddTags(placeUuids: Collection<String>, tagIds: Collection<String>) = repository.bulkAddPlaceTags(placeUuids, tagIds)
    suspend fun bulkRemoveTags(placeUuids: Collection<String>, tagIds: Collection<String>) = repository.bulkRemovePlaceTags(placeUuids, tagIds)

    suspend fun save(mutation: PlaceMutation): String {
        val uuid = repository.savePlace(
            uuid = mutation.uuid,
            nickname = mutation.nickname,
            address = mutation.address,
            lat = mutation.lat,
            lon = mutation.lon,
            radiusM = mutation.radiusM,
            notes = mutation.notes,
            photoUri = mutation.photoUri,
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
