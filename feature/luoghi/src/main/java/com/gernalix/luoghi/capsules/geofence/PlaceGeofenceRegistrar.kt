package com.gernalix.luoghi.capsules.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.content.ContextCompat
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.data.PlaceDao
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class PlaceGeofenceRegistrar(
    private val context: Context,
    private val dao: PlaceDao,
) {
    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getGeofencingClient(appContext) }

    suspend fun reconcile(): PlaceGeofenceResult {
        val configs = dao.enabledGeofenceConfigs()
        val places = dao.placesByUuids(configs.map { it.placeUuid }).associateBy { it.uuid }
        val geofences = configs.mapNotNull { config ->
            val place = places[config.placeUuid]?.takeUnless { it.archived } ?: return@mapNotNull null
            val lat = place.lat ?: return@mapNotNull null
            val lon = place.lon ?: return@mapNotNull null
            if (!com.gernalix.luoghi.capsules.mapviewer.OsmAndMapPlan.valid(lat, lon)) return@mapNotNull null
            val transitions = buildList {
                if (config.enterEnabled) add(Geofence.GEOFENCE_TRANSITION_ENTER)
                if (config.exitEnabled) add(Geofence.GEOFENCE_TRANSITION_EXIT)
            }.fold(0) { acc, value -> acc or value }
            if (transitions == 0) return@mapNotNull null
            Geofence.Builder()
                .setRequestId(place.uuid)
                .setCircularRegion(lat, lon, CheckInPolicy.effectiveRadiusM(place).toFloat())
                .setTransitionTypes(transitions)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }
        if (geofences.isNotEmpty() && !hasBackgroundLocationPermission(appContext)) return PlaceGeofenceResult.PermissionMissing
        return try {
            val completed = withTimeoutOrNull(10_000L) {
                removeGeofences()
                if (geofences.isNotEmpty()) addGeofences(geofences)
                true
            } ?: false
            when {
                !completed -> PlaceGeofenceResult.RegistrationFailed
                configs.any { config ->
                    val place = places[config.placeUuid]
                    val lat = place?.lat
                    val lon = place?.lon
                    (config.enterEnabled || config.exitEnabled) && place != null && !place.archived &&
                        (lat == null || lon == null || !com.gernalix.luoghi.capsules.mapviewer.OsmAndMapPlan.valid(lat, lon))
                } -> PlaceGeofenceResult.CoordinatesMissing
                else -> PlaceGeofenceResult.Success
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PlaceGeofenceResult.RegistrationFailed
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun addGeofences(geofences: List<Geofence>) {
        client.addGeofences(
            GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofences(geofences)
                .build(),
            pendingIntent(),
        ).asUnit()
    }

    suspend fun removeGeofences() {
        client.removeGeofences(pendingIntent()).asUnit()
    }

    private fun pendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_MUTABLE
        val intent = Intent(appContext, PlaceGeofenceReceiver::class.java).apply {
            action = PlaceGeofenceReceiver.ACTION_GEOFENCE_TRANSITION
            data = "places://geofence/${DatabaseProfiles.activeProfileId(appContext)}/transitions".toUri()
            putExtra(PROFILE_ID, DatabaseProfiles.activeProfileId(appContext))
        }
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    companion object {
        const val PROFILE_ID = "profile_id"
        fun hasBackgroundLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    }
}

private suspend fun com.google.android.gms.tasks.Task<Void>.asUnit(): Unit =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(Unit) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.resumeWithException(java.util.concurrent.CancellationException()) }
    }
