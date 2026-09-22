package com.gernalix.personalhub.core.location

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

enum class LocationSuggestionSource {
    ExistingPlace,
    Google,
}

data class LocationSuggestion(
    val id: String,
    val primaryText: String,
    val secondaryText: String,
    val source: LocationSuggestionSource,
    val resolvedAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class ResolvedLocation(
    val id: String,
    val displayName: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val source: LocationSuggestionSource,
)

interface LocationSuggestionProvider {
    val source: LocationSuggestionSource
    val isConfigured: Boolean
    suspend fun search(query: String): List<LocationSuggestion>
    suspend fun resolve(suggestion: LocationSuggestion): ResolvedLocation
    fun resetSession() = Unit
}

/** Queries canonical PersonalHub Places directly; no feature-to-feature dependency or ContentProvider mirror. */
class ExistingPlacesLocationProvider(
    context: Context,
    private val limit: Int = 20,
) : LocationSuggestionProvider {
    private val appContext = context.applicationContext
    override val source: LocationSuggestionSource = LocationSuggestionSource.ExistingPlace
    override val isConfigured: Boolean = true

    override suspend fun search(query: String): List<LocationSuggestion> =
        PersonalHubDatabase.get(appContext)
            .placeDao()
            .searchForHub(query.trim(), limit)
            .map { place ->
                LocationSuggestion(
                    id = place.uuid,
                    primaryText = place.nickname.ifBlank { place.address.orEmpty() },
                    secondaryText = place.address.orEmpty(),
                    source = source,
                    resolvedAddress = place.address,
                    latitude = place.lat,
                    longitude = place.lon,
                )
            }

    override suspend fun resolve(suggestion: LocationSuggestion): ResolvedLocation {
        require(suggestion.source == source) { "Suggestion does not belong to Existing Places." }
        val place = PersonalHubDatabase.get(appContext).placeDao().getPlace(suggestion.id)
            ?: error("The selected PersonalHub place no longer exists.")
        return ResolvedLocation(
            id = place.uuid,
            displayName = place.nickname,
            address = place.address.orEmpty(),
            latitude = place.lat,
            longitude = place.lon,
            source = source,
        )
    }
}

class GooglePlacesLocationProvider(
    context: Context,
    private val apiKey: String,
    private val biasLatitude: Double = DEFAULT_BIAS_LATITUDE,
    private val biasLongitude: Double = DEFAULT_BIAS_LONGITUDE,
    private val biasRadiusMeters: Double = DEFAULT_BIAS_RADIUS_METERS,
) : LocationSuggestionProvider {
    private val appContext = context.applicationContext
    private var placesClient: PlacesClient? = null
    private var sessionToken: AutocompleteSessionToken? = null

    override val source: LocationSuggestionSource = LocationSuggestionSource.Google
    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    override suspend fun search(query: String): List<LocationSuggestion> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_GOOGLE_QUERY_CHARS || !isConfigured) return emptyList()

        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(trimmed)
            .setSessionToken(sessionToken())
            .setLocationBias(
                CircularBounds.newInstance(
                    LatLng(biasLatitude, biasLongitude),
                    biasRadiusMeters,
                )
            )
            .build()

        return client()
            .findAutocompletePredictions(request)
            .await()
            .autocompletePredictions
            .map { prediction ->
                LocationSuggestion(
                    id = prediction.placeId,
                    primaryText = prediction.getPrimaryText(null).toString(),
                    secondaryText = prediction.getSecondaryText(null).toString(),
                    source = source,
                )
            }
    }

    @Suppress("DEPRECATION")
    override suspend fun resolve(suggestion: LocationSuggestion): ResolvedLocation {
        require(suggestion.source == source) { "Suggestion does not belong to Google Places." }
        check(isConfigured) { "Google Places API key is missing." }
        val request = FetchPlaceRequest.builder(
            suggestion.id,
            listOf(Place.Field.ADDRESS, Place.Field.LAT_LNG, Place.Field.NAME),
        ).setSessionToken(sessionToken()).build()

        val place = client().fetchPlace(request).await().place
        val latLng = place.latLng ?: error("Selected address has no coordinates.")
        sessionToken = null
        return ResolvedLocation(
            id = suggestion.id,
            displayName = place.name ?: suggestion.primaryText,
            address = place.address ?: place.name ?: suggestion.primaryText,
            latitude = latLng.latitude,
            longitude = latLng.longitude,
            source = source,
        )
    }

    override fun resetSession() {
        sessionToken = null
    }

    private fun client(): PlacesClient {
        placesClient?.let { return it }
        if (!Places.isInitialized()) {
            Log.i(LOG_TAG, "Initializing shared Google Places suggestion provider")
            Places.initializeWithNewPlacesApiEnabled(appContext, apiKey)
        }
        return Places.createClient(appContext).also { placesClient = it }
    }

    private fun sessionToken(): AutocompleteSessionToken =
        sessionToken ?: AutocompleteSessionToken.newInstance().also { sessionToken = it }

    private companion object {
        const val LOG_TAG = "HubLocationSuggestions"
        const val MIN_GOOGLE_QUERY_CHARS = 3
        const val DEFAULT_BIAS_LATITUDE = 55.6761
        const val DEFAULT_BIAS_LONGITUDE = 12.5683
        const val DEFAULT_BIAS_RADIUS_METERS = 50_000.0
    }
}

class LocationSuggestionEngine(
    private val providers: List<LocationSuggestionProvider>,
    private val maxSuggestions: Int = 20,
) {
    val isConfigured: Boolean get() = providers.any { it.isConfigured }

    suspend fun search(query: String): List<LocationSuggestion> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val seen = linkedSetOf<String>()
        return buildList {
            providers.filter { it.isConfigured }.forEach { provider ->
                provider.search(trimmed).forEach { suggestion ->
                    val key = "${suggestion.source}:${suggestion.id}"
                    if (seen.add(key)) add(suggestion)
                }
            }
        }.take(maxSuggestions)
    }

    suspend fun resolve(suggestion: LocationSuggestion): ResolvedLocation =
        providers.firstOrNull { it.source == suggestion.source }
            ?.resolve(suggestion)
            ?: error("No provider is registered for ${suggestion.source}.")

    fun resetSession() {
        providers.forEach(LocationSuggestionProvider::resetSession)
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
