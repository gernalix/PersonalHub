package com.gernalix.luoghi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.gernalix.luoghi.capsules.mapviewer.*
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compatibility entry point for existing PH map intents; rendering belongs entirely to OsmAnd. */
class MapViewerActivity : ComponentActivity() {
    private lateinit var bridge: OsmAndBridge
    private var opening: Job? = null
    private var status by mutableStateOf("Opening OsmAnd…")
    private var unavailable by mutableStateOf(false)
    private var mapAccessRequired by mutableStateOf(false)
    private val requestMapAccess = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { openMap() }
    private var launched = false
    private var leftForMap = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        bridge = OsmAndBridge(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("OsmAnd", style = MaterialTheme.typography.headlineMedium)
                Text(status)
                if (unavailable) Button(onClick = {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://osmand.net/docs/versions/free-versions/")))
                }) { Text("Install OsmAnd") }
                if (mapAccessRequired) Button(onClick = {
                    val target = bridge.installedPackage() ?: return@Button
                    val request = Intent("net.osmand.aidl.REQUEST_PERMISSIONS").setPackage(target)
                        .putExtra("groups", arrayOf("map"))
                    if (request.resolveActivity(packageManager) != null) requestMapAccess.launch(request)
                    else packageManager.getLaunchIntentForPackage(target)?.let(::startActivity)
                }) { Text("Allow map access in OsmAnd") }
                TextButton(onClick = ::openMap) { Text("Try again") }
                TextButton(onClick = { finish() }) { Text("Back") }
            }
        } } }
        openMap()
    }

    private fun openMap() {
        if (opening?.isActive == true) return
        if (bridge.installedPackage() == null) {
            unavailable = true
            status = "OsmAnd is optional. Install it to view the map; your places and search remain available in PersonalHub."
            return
        }
        unavailable = false
        mapAccessRequired = false
        opening = lifecycleScope.launch {
            try {
                status = "Preparing places in OsmAnd…"
                val model = withContext(Dispatchers.IO) {
                    MapViewerRepository(PersonalHubDatabase.get(applicationContext).placeDao()).load(
                        intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.map_default_title),
                        MapCallerMode.parse(intent.getStringExtra(EXTRA_CALLER_MODE)),
                        MapMarkerMode.parse(intent.getStringExtra(EXTRA_MARKER_MODE)),
                        intent.getStringArrayListExtra(EXTRA_PLACE_UUIDS).orEmpty(),
                        intent.getStringArrayListExtra(EXTRA_LABELS).orEmpty(),
                    )
                }
                val reference = if (intent.hasExtra(EXTRA_REFERENCE_LAT) && intent.hasExtra(EXTRA_REFERENCE_LON)) {
                    OsmAndReference(intent.getDoubleExtra(EXTRA_REFERENCE_LAT, Double.NaN),
                        intent.getDoubleExtra(EXTRA_REFERENCE_LON, Double.NaN),
                        intent.getStringExtra(EXTRA_REFERENCE_LABEL) ?: "Reference", intent.getStringExtra(EXTRA_REFERENCE_ID))
                } else {
                    val first = model.markers.firstOrNull() ?: error("No saved places have valid coordinates")
                    OsmAndReference(first.latitude, first.longitude, first.title, first.uuid)
                }
                bridge.connect()
                bridge.publish(model.markers, reference)
                launched = true
                bridge.showReference(reference)
                status = "Return to PersonalHub to close this temporary map."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                launched = false
                leftForMap = false
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { bridge.clear() } }
                mapAccessRequired = error.message == "Allow PersonalHub map access in OsmAnd external app settings"
                status = if (mapAccessRequired) "In OsmAnd, open Menu → Plugins → PersonalHub and enable it. Then return here and choose Try again. PersonalHub uses only temporary map points."
                    else error.message?.takeIf { it in SAFE_ERRORS } ?: "Unable to open the map. Check that OsmAnd allows PersonalHub to access maps, then try again."
                bridge.close()
            }
        }
    }

    override fun onPause() { super.onPause(); if (launched) leftForMap = true }
    override fun onResume() {
        super.onResume()
        if (leftForMap && ::bridge.isInitialized) {
            leftForMap = false
            lifecycleScope.launch { try { runCatching { bridge.clear() } } finally { bridge.close(); finish() } }
        }
    }
    override fun onDestroy() {
        if (::bridge.isInitialized) {
            val ownedBridge = bridge
            // Binder cleanup does not block the UI or introduce a long-lived service.
            Thread({ kotlinx.coroutines.runBlocking { try { runCatching { ownedBridge.clear() } } finally { ownedBridge.close() } } }, "ph-map-cleanup").start()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW_MAP = "com.gernalix.luoghi.action.SHOW_MAP"
        const val EXTRA_PLACE_UUIDS = "com.gernalix.luoghi.extra.PLACE_UUIDS"
        const val EXTRA_TITLE = "com.gernalix.luoghi.extra.TITLE"
        const val EXTRA_CALLER_MODE = "com.gernalix.luoghi.extra.CALLER_MODE"
        const val EXTRA_LABELS = "com.gernalix.luoghi.extra.LABELS"
        const val EXTRA_MARKER_MODE = "com.gernalix.luoghi.extra.MARKER_MODE"
        const val EXTRA_REFERENCE_LAT = "com.gernalix.luoghi.extra.REFERENCE_LAT"
        const val EXTRA_REFERENCE_LON = "com.gernalix.luoghi.extra.REFERENCE_LON"
        const val EXTRA_REFERENCE_LABEL = "com.gernalix.luoghi.extra.REFERENCE_LABEL"
        const val EXTRA_REFERENCE_ID = "com.gernalix.luoghi.extra.REFERENCE_ID"
        private val SAFE_ERRORS = setOf("No saved places have valid coordinates", "OsmAnd is not installed")
    }
}
