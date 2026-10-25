package com.gernalix.luoghi.capsules.geometadata

import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceGeoMetadataEntity
import org.json.JSONObject

/**
 * Persists the complete provider response (not just the displayed address). Only values useful
 * for finding a place are projected into the local search text; no query ever hits the network.
 */
internal object PlaceGeoMetadataCodec {
    fun found(place: PlaceEntity, json: String): PlaceGeoMetadataEntity {
        val root = JSONObject(json)
        require(!root.has("error")) { "Reverse geocoding returned an error" }
        val names = buildList {
            listOf("display_name", "name", "category", "type", "addresstype").forEach { key ->
                root.optString(key).takeIf(String::isNotBlank)?.let(::add)
            }
            for (field in listOf("address", "namedetails", "extratags")) {
                root.optJSONObject(field)?.let { obj ->
                    obj.keys().forEach { key ->
                        // Include every available geographic name, street, postcode and variant.
                        val value = obj.opt(key)
                        if (value is String && value.isNotBlank()) add(value)
                    }
                }
            }
        }.distinct().joinToString(" ")
        return record(place, "FOUND", json, names)
    }

    fun noMatch(place: PlaceEntity): PlaceGeoMetadataEntity =
        record(place, "NO_MATCH", "{}", "")

    private fun record(place: PlaceEntity, status: String, raw: String, text: String) =
        PlaceGeoMetadataEntity(
            placeUuid = place.uuid,
            latitudeSnapshot = requireNotNull(place.lat),
            longitudeSnapshot = requireNotNull(place.lon),
            status = status,
            rawJson = raw,
            searchText = text,
        )
}
