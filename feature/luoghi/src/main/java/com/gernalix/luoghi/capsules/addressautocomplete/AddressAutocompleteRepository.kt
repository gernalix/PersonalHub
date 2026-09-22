package com.gernalix.luoghi.capsules.addressautocomplete

import android.content.Context
import com.gernalix.luoghi.BuildConfig
import com.gernalix.personalhub.core.location.GooglePlacesLocationProvider
import com.gernalix.personalhub.core.location.LocationSuggestion
import com.gernalix.personalhub.core.location.LocationSuggestionSource

data class AddressSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String,
    val source: AddressSuggestionSource = AddressSuggestionSource.Google,
    val resolvedAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class ResolvedAddress(
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
)

enum class AddressSuggestionSource {
    Local,
    Google,
}

interface AddressAutocompleteSource {
    val isConfigured: Boolean
    suspend fun search(query: String): List<AddressSuggestion>
    suspend fun resolve(suggestion: AddressSuggestion): ResolvedAddress
    fun resetSession()
}

class AddressAutocompleteRepository(
    context: Context,
) : AddressAutocompleteSource {
    private val provider = GooglePlacesLocationProvider(
        context = context.applicationContext,
        apiKey = BuildConfig.GOOGLE_MAPS_API_KEY,
    )

    override val isConfigured: Boolean
        get() = provider.isConfigured

    override suspend fun search(query: String): List<AddressSuggestion> =
        provider.search(query)
            .map { suggestion ->
                AddressSuggestion(
                    placeId = suggestion.id,
                    primaryText = suggestion.primaryText,
                    secondaryText = suggestion.secondaryText,
                    source = AddressSuggestionSource.Google,
                    resolvedAddress = suggestion.resolvedAddress,
                    latitude = suggestion.latitude,
                    longitude = suggestion.longitude,
                )
            }
            .let(AddressAutocompletePolicy::limitSuggestions)

    override suspend fun resolve(suggestion: AddressSuggestion): ResolvedAddress {
        val resolved = provider.resolve(
            LocationSuggestion(
                id = suggestion.placeId,
                primaryText = suggestion.primaryText,
                secondaryText = suggestion.secondaryText,
                source = LocationSuggestionSource.Google,
                resolvedAddress = suggestion.resolvedAddress,
                latitude = suggestion.latitude,
                longitude = suggestion.longitude,
            )
        )
        return ResolvedAddress(
            address = resolved.address,
            latitude = resolved.latitude,
            longitude = resolved.longitude,
        )
    }

    override fun resetSession() {
        provider.resetSession()
    }
}
