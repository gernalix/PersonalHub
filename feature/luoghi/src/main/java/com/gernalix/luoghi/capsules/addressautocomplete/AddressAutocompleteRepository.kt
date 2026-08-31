package com.gernalix.luoghi.capsules.addressautocomplete

import android.content.Context
import android.util.Log
import com.gernalix.luoghi.BuildConfig
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

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
    private val context: Context,
) : AddressAutocompleteSource {
    private var placesClient: PlacesClient? = null
    private var sessionToken: AutocompleteSessionToken? = null

    override val isConfigured: Boolean
        get() = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()

    override suspend fun search(query: String): List<AddressSuggestion> {
        val trimmed = query.trim()
        if (trimmed.length < 3 || !isConfigured) {
            Log.i(LOG_TAG, "Skipping Google Places search configured=$isConfigured queryLength=${trimmed.length}")
            return emptyList()
        }

        val requestBuilder = FindAutocompletePredictionsRequest.builder()
            .setQuery(trimmed)
            .setSessionToken(sessionToken())

        safeLocationBias().let(requestBuilder::setLocationBias)

        Log.i(LOG_TAG, "Requesting Google Places predictions queryLength=${trimmed.length}")
        return client()
            .findAutocompletePredictions(requestBuilder.build())
            .await()
            .autocompletePredictions
            .also { predictions ->
                Log.i(LOG_TAG, "Received Google Places predictions count=${predictions.size}")
            }
            .map { prediction ->
                AddressSuggestion(
                    placeId = prediction.placeId,
                    primaryText = prediction.getPrimaryText(null).toString(),
                    secondaryText = prediction.getSecondaryText(null).toString(),
                    source = AddressSuggestionSource.Google,
                )
            }
            .let(AddressAutocompletePolicy::limitSuggestions)
    }

    @Suppress("DEPRECATION")
    override suspend fun resolve(suggestion: AddressSuggestion): ResolvedAddress {
        check(isConfigured) { "Google Places API key is missing." }

        val request = FetchPlaceRequest.builder(
            suggestion.placeId,
            listOf(Place.Field.ADDRESS, Place.Field.LAT_LNG, Place.Field.NAME),
        ).setSessionToken(sessionToken()).build()

        val place = client().fetchPlace(request).await().place
        val latLng = place.latLng ?: error("Selected address has no coordinates.")
        sessionToken = null
        Log.i(LOG_TAG, "Resolved Google Places address latLngPresent=true")

        return ResolvedAddress(
            address = place.address ?: place.name ?: suggestion.primaryText,
            latitude = latLng.latitude,
            longitude = latLng.longitude,
        )
    }

    override fun resetSession() {
        sessionToken = null
    }

    private fun client(): PlacesClient {
        placesClient?.let { return it }
        if (!Places.isInitialized()) {
            Log.i(LOG_TAG, "Initializing Google Places address autocomplete")
            Places.initializeWithNewPlacesApiEnabled(
                context.applicationContext,
                BuildConfig.GOOGLE_MAPS_API_KEY,
            )
        }
        return Places.createClient(context.applicationContext).also { placesClient = it }
    }

    private fun sessionToken(): AutocompleteSessionToken =
        sessionToken ?: AutocompleteSessionToken.newInstance().also { sessionToken = it }

    private fun safeLocationBias(): CircularBounds =
        CircularBounds.newInstance(
            LatLng(DEFAULT_BIAS_LATITUDE, DEFAULT_BIAS_LONGITUDE),
            MAX_PLACES_RADIUS_METERS,
        )

    private companion object {
        const val LOG_TAG = "AddressAutocomplete"
        const val MAX_PLACES_RADIUS_METERS = 50_000.0
        const val DEFAULT_BIAS_LATITUDE = 55.6761
        const val DEFAULT_BIAS_LONGITUDE = 12.5683
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
