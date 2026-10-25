package com.gernalix.luoghi.capsules.places

import com.gernalix.luoghi.data.PlaceEntity
import java.text.Normalizer
import java.util.Locale

/** Matches meaningful place fields and tag names without exposing database identifiers. */
object PlaceSearch {
    fun matches(place: PlaceEntity, tagNames: Collection<String>, query: String, geoSearchText: String = ""): Boolean {
        val terms = normalize(query).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (terms.isEmpty()) return true
        val searchable = normalize((listOf(place.nickname, place.address.orEmpty(), place.notes.orEmpty(), geoSearchText) + tagNames).joinToString(" "))
        return terms.all(searchable::contains)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}
