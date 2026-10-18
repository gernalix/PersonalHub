package com.gernalix.luoghi.capsules.mapviewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.AddMapPointParams
import net.osmand.aidlapi.maplayer.point.ShowMapPointParams
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OsmAndReference(val latitude: Double, val longitude: Double, val label: String, val placeId: String? = null)
data class OsmAndPoint(val id: String, val latitude: Double, val longitude: Double, val label: String, val reference: Boolean)

object OsmAndMapPlan {
    fun valid(latitude: Double, longitude: Double): Boolean = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun points(markers: List<MapMarkerModel>, reference: OsmAndReference): List<OsmAndPoint> {
        require(valid(reference.latitude, reference.longitude))
        return listOf(OsmAndPoint("ph-reference", reference.latitude, reference.longitude, reference.label, true)) + markers
            .filter { it.uuid != reference.placeId && valid(it.latitude, it.longitude) }
            .distinctBy { it.uuid }.map { OsmAndPoint("ph-place:${it.uuid}", it.latitude, it.longitude, it.title, false) }
    }
}

/** One explicit, short-lived bound connection; never edits favorites or requests device location. */
class OsmAndBridge(context: Context) {
    private val app = context.applicationContext
    private var api: IOsmAndAidlInterface? = null
    private var connection: ServiceConnection? = null
    private var bound = false
    var packageName: String? = null
        private set

    fun installedPackage(): String? = PACKAGES.firstOrNull { name ->
        runCatching { app.packageManager.getApplicationInfo(name, 0).enabled }.getOrDefault(false)
    }

    suspend fun connect() = withContext(Dispatchers.Main.immediate) {
        if (api != null) return@withContext
        val target = installedPackage() ?: error("OsmAnd is not installed")
        packageName = target
        try {
            withTimeout(15_000L) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val connected = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (continuation.isActive) {
                                api = IOsmAndAidlInterface.Stub.asInterface(binder)
                                continuation.resume(Unit)
                            } else close()
                        }
                        override fun onServiceDisconnected(name: ComponentName) { api = null }
                        override fun onNullBinding(name: ComponentName) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("OsmAnd connection unavailable"))
                        }
                        override fun onBindingDied(name: ComponentName) {
                            api = null
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("OsmAnd connection ended"))
                        }
                    }
                    connection = connected
                    val flags = Context.BIND_AUTO_CREATE or
                        if (android.os.Build.VERSION.SDK_INT >= 34) Context.BIND_ALLOW_ACTIVITY_STARTS else 0
                    bound = app.bindService(Intent("net.osmand.aidl.OsmandAidlServiceV2").setPackage(target), connected, flags)
                    if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("OsmAnd connection refused"))
                    continuation.invokeOnCancellation { close() }
                }
            }
        } catch (error: Exception) { close(); throw error }
    }

    suspend fun publish(markers: List<MapMarkerModel>, reference: OsmAndReference) = withContext(Dispatchers.IO) {
        val service = checkNotNull(api)
        val points = OsmAndMapPlan.points(markers, reference)
        service.removeMapLayer(RemoveMapLayerParams(LAYER_ID))
        check(service.addMapLayer(AddMapLayerParams(AMapLayer(LAYER_ID, "PersonalHub", 5.5f, emptyList()).apply {
            setImagePoints(false)
            setCirclePointZoomBounds(1, 6)
            setSmallPointZoomBounds(7, 13)
            setBigPointZoomBounds(14, 22)
        }))) { "Allow PersonalHub map access in OsmAnd external app settings" }
        try {
            for (point in points) {
                coroutineContext.ensureActive()
                val marker = AMapPoint(point.id, point.label, point.label,
                    if (point.reference) "PersonalHub reference" else "PersonalHub place", LAYER_ID,
                    if (point.reference) 0xFFFF6D00.toInt() else 0xFF168A83.toInt(),
                    ALatLon(point.latitude, point.longitude), emptyList(), emptyMap())
                check(service.addMapPoint(AddMapPointParams(LAYER_ID, marker))) { "OsmAnd could not display all places" }
            }

        } catch (error: Exception) { runCatching { service.removeMapLayer(RemoveMapLayerParams(LAYER_ID)) }; throw error }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { api?.removeMapLayer(RemoveMapLayerParams(LAYER_ID)); Unit }

    /** Upstream stores the target before opening its map, so cold starts keep center and zoom. */
    suspend fun showReference(reference: OsmAndReference) = withContext(Dispatchers.IO) {
        check(checkNotNull(api).showMapPoint(ShowMapPointParams(LAYER_ID,
            AMapPoint("ph-reference", reference.label, reference.label, "PersonalHub reference", LAYER_ID,
                0xFFFF6D00.toInt(), ALatLon(reference.latitude, reference.longitude), emptyList(), emptyMap())))) {
            "OsmAnd could not show the reference"
        }
    }

    fun close() {
        api = null
        if (bound) connection?.let { runCatching { app.unbindService(it) } }
        bound = false
        connection = null
    }

    companion object {
        const val LAYER_ID = "personalhub-places"
        val PACKAGES = listOf("net.osmand", "net.osmand.plus", "net.osmand.dev")
    }
}
