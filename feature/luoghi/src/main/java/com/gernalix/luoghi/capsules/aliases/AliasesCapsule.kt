package com.gernalix.luoghi.capsules.aliases

import com.gernalix.luoghi.data.PlaceRepository

class AliasesCapsule(private val repository: PlaceRepository) {
    suspend fun create(placeUuid: String, alias: String, appScope: String? = null): Long =
        repository.createAlias(placeUuid, alias, appScope)

    suspend fun update(id: Long, placeUuid: String, alias: String, appScope: String? = null): Long =
        repository.updateAlias(id, placeUuid, alias, appScope)

    fun delete(id: Long): Int = repository.deleteAlias(id)
}
