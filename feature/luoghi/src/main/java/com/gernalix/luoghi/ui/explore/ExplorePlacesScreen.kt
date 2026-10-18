package com.gernalix.luoghi.ui.explore

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.capsules.addressautocomplete.*
import com.gernalix.luoghi.capsules.explore.PlacesProximity
import com.gernalix.luoghi.capsules.mapviewer.MapViewerCapsule
import com.gernalix.luoghi.capsules.mapviewer.OsmAndReference
import com.gernalix.luoghi.capsules.mapviewer.OsmAndMapPlan
import java.util.Locale
import com.gernalix.luoghi.capsules.places.PlaceSearch
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.ui.common.localizedDistance
import com.gernalix.personalhub.core.ui.HubSearchField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToLong

/** Uses existing address lookup; selecting a temporary reference performs no database writes. */
@Composable
fun ExplorePlacesScreen(places: List<PlaceEntity>, onBack: () -> Unit, onOpenPlace: (PlaceEntity) -> Unit) {
    val context = LocalContext.current
    val source = remember { AddressAutocompleteRepository(context) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var label by rememberSaveable { mutableStateOf("") }
    var referenceId by rememberSaveable { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<AddressSuggestion>()) }
    var error by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    val reference = if (validCoordinates(latitude, longitude)) OsmAndReference(latitude!!, longitude!!, label, referenceId) else null
    val ordered = remember(places, reference) { reference?.let { PlacesProximity.ordered(places, it) }.orEmpty() }
    val firstMissingId = remember(ordered) { ordered.firstOrNull { it.distanceMeters == null }?.place?.uuid }
    val saved = remember(places, query, reference) {
        if (reference != null || query.isBlank()) emptyList() else places.filter {
            PlaceSearch.matches(it, emptyList(), query)
        }.sortedWith(compareBy<PlaceEntity> { it.nickname.lowercase(Locale.ROOT) }.thenBy { it.uuid }).take(10)
    }
    LaunchedEffect(query, reference) {
        suggestions = emptyList()
        error = false
        if (reference == null && AddressAutocompletePolicy.shouldSearch(query, source.isConfigured)) {
            delay(AddressAutocompletePolicy.DEBOUNCE_MS)
            try { suggestions = withTimeout(AddressAutocompletePolicy.SEARCH_TIMEOUT_MS) { source.search(query) } }
            catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) error = true else throw cancelled
            }
            catch (_: Exception) { error = true }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Explore places", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            HubSearchField(value = query, onValueChange = {
                query = it
                latitude = null
                longitude = null
                referenceId = null
            }, label = "Choose a saved place or search an address", modifier = Modifier.fillMaxWidth().testTag("explore-reference-search"))
        }
        items(saved, key = { "saved:${it.uuid}" }) { place ->
            TextButton(enabled = !resolving && validCoordinates(place.lat, place.lon), onClick = {
                latitude = place.lat; longitude = place.lon; label = place.nickname; referenceId = place.uuid
                query = place.nickname; suggestions = emptyList()
            }, modifier = Modifier.fillMaxWidth()) { Text(place.nickname + (place.address?.let { " · $it" } ?: "")) }
        }
        items(suggestions, key = { "address:${it.placeId}" }) { suggestion ->
            TextButton(enabled = !resolving, onClick = {
                val requestedQuery = query
                resolving = true
                scope.launch {
                    try {
                        val resolved = withTimeout(AddressAutocompletePolicy.RESOLVE_TIMEOUT_MS) { source.resolve(suggestion) }
                        if (query == requestedQuery && reference == null) {
                            latitude = resolved.latitude; longitude = resolved.longitude
                            label = resolved.address; query = resolved.address; referenceId = null
                            suggestions = emptyList()
                            if (!validCoordinates(resolved.latitude, resolved.longitude)) error = true
                        }
                    } catch (cancelled: CancellationException) {
                        if (cancelled is kotlinx.coroutines.TimeoutCancellationException) error = true else throw cancelled
                    } catch (_: Exception) { error = true }
                    finally { resolving = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(suggestion.primaryText + " · " + suggestion.secondaryText) }
        }
        if (resolving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error) item { Text("Address lookup failed. Try another address or choose a saved place.", color = MaterialTheme.colorScheme.error) }
        if (reference == null) item {
            Text("Choose the reference above. Distances use that point, not your GPS location. Temporary addresses are not saved.")
        } else {
            item {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text("Straight-line distances from this reference; moving the map does not change them.")
                Button(onClick = { context.startActivity(MapViewerCapsule.exploreMapIntent(context, reference)) }, modifier = Modifier.testTag("explore-open-osmand")) {
                    Text("Show in OsmAnd")
                }
            }
            items(ordered, key = { "nearby:${it.place.uuid}" }) { nearby ->
                if (nearby.distanceMeters == null && firstMissingId == nearby.place.uuid) {
                    Text("Places without coordinates", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedCard(onClick = { onOpenPlace(nearby.place) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(nearby.place.nickname, modifier = Modifier.weight(1f))
                        Text(nearby.distanceMeters?.roundToLong()?.let { localizedDistance(it) } ?: "No coordinates")
                    }
                }
            }
        }
    }
}

private fun validCoordinates(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && OsmAndMapPlan.valid(latitude, longitude)
