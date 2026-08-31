package com.gernalix.luoghi.capsules.links

import com.gernalix.luoghi.data.PlaceRepository

class LinksCapsule(private val repository: PlaceRepository) {
    suspend fun create(placeUuid: String, ownerApp: String, ownerType: String, ownerId: String): Long =
        repository.createLink(placeUuid, ownerApp, ownerType, ownerId)

    suspend fun update(id: Long, placeUuid: String, ownerApp: String, ownerType: String, ownerId: String): Long =
        repository.updateLink(id, placeUuid, ownerApp, ownerType, ownerId)

    fun delete(id: Long): Int = repository.deleteLink(id)
}
