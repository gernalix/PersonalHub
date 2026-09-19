package com.gernalix.luoghi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gernalix.luoghi.capsules.mapviewer.MapCallerMode
import com.gernalix.luoghi.capsules.location.FusedLocationCapsule
import com.gernalix.luoghi.capsules.mapviewer.MapCurrentLocation
import com.gernalix.luoghi.capsules.mapviewer.MapMarkerMode
import com.gernalix.luoghi.capsules.mapviewer.MapOverlayMarker
import com.gernalix.luoghi.capsules.mapviewer.MapViewerModel
import com.gernalix.luoghi.capsules.mapviewer.MapViewerRepository
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MapViewerActivity : ComponentActivity() {
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.map_default_title)
        val callerMode = MapCallerMode.parse(intent.getStringExtra(EXTRA_CALLER_MODE))
        val markerMode = MapMarkerMode.parse(intent.getStringExtra(EXTRA_MARKER_MODE))
        val uuids = intent.stringListExtra(EXTRA_PLACE_UUIDS)
        val labels = intent.stringListExtra(EXTRA_LABELS)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var state by remember { mutableStateOf<MapViewerUiState>(MapViewerUiState.Loading) }
                    LaunchedEffect(title, callerMode, markerMode, uuids, labels) {
                        state = runCatching {
                            withContext(Dispatchers.IO) {
                                val currentLocation = if (callerMode == MapCallerMode.GLOBAL) {
                                    FusedLocationCapsule(applicationContext).currentLocation()?.let {
                                        MapCurrentLocation(it.latitude, it.longitude)
                                    }
                                } else {
                                    null
                                }
                                MapViewerRepository(PersonalHubDatabase.get(applicationContext).placeDao())
                                    .load(title, callerMode, markerMode, uuids, labels, currentLocation)
                            }
                        }.fold(
                            onSuccess = { MapViewerUiState.Ready(it) },
                            onFailure = { MapViewerUiState.Fallback(title, it.message ?: getString(R.string.map_fallback_error)) },
                        )
                    }
                    MapViewerContent(state) { mapView = it }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    companion object {
        const val ACTION_SHOW_MAP = "com.gernalix.luoghi.action.SHOW_MAP"
        const val EXTRA_PLACE_UUIDS = "com.gernalix.luoghi.extra.PLACE_UUIDS"
        const val EXTRA_TITLE = "com.gernalix.luoghi.extra.TITLE"
        const val EXTRA_CALLER_MODE = "com.gernalix.luoghi.extra.CALLER_MODE"
        const val EXTRA_LABELS = "com.gernalix.luoghi.extra.LABELS"
        const val EXTRA_MARKER_MODE = "com.gernalix.luoghi.extra.MARKER_MODE"
    }
}

private sealed interface MapViewerUiState {
    data object Loading : MapViewerUiState
    data class Ready(val model: MapViewerModel) : MapViewerUiState
    data class Fallback(val title: String, val reason: String) : MapViewerUiState
}

@Composable
private fun MapViewerContent(
    state: MapViewerUiState,
    onMapReady: (MapView) -> Unit,
) {
    when (state) {
        MapViewerUiState.Loading -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.map_default_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is MapViewerUiState.Fallback -> MapFallback(state.title, state.reason)
        is MapViewerUiState.Ready -> {
            if (state.model.markers.isEmpty()) {
                MapFallback(state.model.title, stringResource(R.string.map_no_markers))
            } else {
                RealMap(model = state.model, onMapReady = onMapReady)
            }
        }
    }
}

@Composable
private fun RealMap(
    model: MapViewerModel,
    onMapReady: (MapView) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(model.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.map_mode_format, model.callerMode.name, model.markerMode.name))
            if (model.usesPerformanceClusterFallback) {
                Text(stringResource(R.string.map_cluster_fallback_format, model.markers.size, model.overlays.size))
            }
            if (model.missingCoordinateCount > 0) {
                Text(stringResource(R.string.map_missing_coordinates_format, model.missingCoordinateCount))
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    MapView(context).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        minZoomLevel = 2.0
                        maxZoomLevel = 20.0
                        onMapReady(this)
                    }
                },
                update = { map ->
                    onMapReady(map)
                    map.renderMarkers(model)
                },
            )
        }
    }
}

@Composable
private fun MapFallback(title: String, reason: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.map_fallback_title), fontWeight = FontWeight.SemiBold)
        Text(reason)
    }
}

private fun MapView.renderMarkers(model: MapViewerModel) {
    val signature = model.markers.joinToString("|") { it.uuid } + "@${model.currentLocation}"
    if (tag == signature) return
    tag = signature
    overlays.clear()
    val overlayMarkers = model.overlays
    overlayMarkers.forEach { markerModel ->
        overlays.add(markerFor(markerModel, model.markerMode))
    }
    val current = model.currentLocation
    val center = if (current != null) {
        GeoPoint(current.latitude, current.longitude)
    } else {
        GeoPoint(
            overlayMarkers.map { it.latitude }.average(),
            overlayMarkers.map { it.longitude }.average(),
        )
    }
    controller.setCenter(center)
    controller.setZoom(if (current != null || overlayMarkers.size == 1) 16.0 else 12.0)
    if (model.markerMode == MapMarkerMode.BALLOON_ALL && overlayMarkers.size <= MAX_PERMANENT_BALLOONS) {
        overlays.filterIsInstance<Marker>().forEach { it.showInfoWindow() }
    }
    invalidate()
}

private fun MapView.markerFor(markerModel: MapOverlayMarker, markerMode: MapMarkerMode): Marker =
    Marker(this).apply {
        position = GeoPoint(markerModel.latitude, markerModel.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = markerModel.title
        snippet = markerModel.snippet
        setOnMarkerClickListener { marker, _ ->
            if (markerMode != MapMarkerMode.SIMPLE) marker.showInfoWindow()
            markerModel.uuid?.let { uuid ->
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        data = Uri.parse("personalhub://module/places?placeId=$uuid")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
            true
        }
    }

private fun android.content.Intent.stringListExtra(key: String): List<String> =
    getStringArrayListExtra(key) ?: getStringArrayExtra(key)?.toList().orEmpty()

private const val MAX_PERMANENT_BALLOONS = 50
