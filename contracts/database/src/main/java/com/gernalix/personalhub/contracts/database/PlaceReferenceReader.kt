package com.gernalix.personalhub.contracts.database

/** Stable cross-module contract used to protect canonical Places from dangling references. */
interface PlaceReferenceReader {
    suspend fun referenceCount(placeId: String): Int
}
