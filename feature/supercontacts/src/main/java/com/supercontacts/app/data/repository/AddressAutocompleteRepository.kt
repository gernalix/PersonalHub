package com.supercontacts.app.data.repository

import android.content.Context
import com.gernalix.personalhub.core.location.ExistingPlacesLocationProvider
import com.gernalix.personalhub.core.location.LocationSuggestion
import com.gernalix.personalhub.core.location.LocationSuggestionSource

data class AddressSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String,
    val source: AddressSuggestionSource = AddressSuggestionSource.Luoghi,
)

data class ResolvedAddress(
    val placeId: String,
    val nickname: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
)

enum class AddressSuggestionSource {
    Luoghi,
}

class LuoghiUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * People adapter over the shared PersonalHub location-suggestion provider.
 *
 * The old ContentProvider mirror is intentionally gone: both People and Places now consume the
 * same canonical location engine/data source without a feature-to-feature dependency.
 */
class AddressAutocompleteRepository(
    context: Context,
) {
    private val provider = ExistingPlacesLocationProvider(context.applicationContext)

    suspend fun search(query: String): List<AddressSuggestion> =
        runCatching {
            provider.search(query).map { suggestion ->
                AddressSuggestion(
                    placeId = suggestion.id,
                    primaryText = suggestion.primaryText,
                    secondaryText = suggestion.secondaryText,
                )
            }
        }.getOrElse { error ->
            throw LuoghiUnavailableException("Impossibile leggere i luoghi disponibili.", error)
        }

    suspend fun resolve(suggestion: AddressSuggestion): ResolvedAddress =
        runCatching {
            provider.resolve(
                LocationSuggestion(
                    id = suggestion.placeId,
                    primaryText = suggestion.primaryText,
                    secondaryText = suggestion.secondaryText,
                    source = LocationSuggestionSource.ExistingPlace,
                )
            ).toPeopleResolvedAddress()
        }.getOrElse { error ->
            throw LuoghiUnavailableException("Il luogo selezionato non esiste più in Luoghi.", error)
        }

    suspend fun resolveStableId(stableId: String): ResolvedAddress? =
        runCatching {
            provider.resolve(
                LocationSuggestion(
                    id = stableId,
                    primaryText = stableId,
                    secondaryText = "",
                    source = LocationSuggestionSource.ExistingPlace,
                )
            ).toPeopleResolvedAddress()
        }.getOrNull()

    private fun com.gernalix.personalhub.core.location.ResolvedLocation.toPeopleResolvedAddress() =
        ResolvedAddress(
            placeId = id,
            nickname = displayName,
            address = address,
            latitude = latitude,
            longitude = longitude,
        )
}
