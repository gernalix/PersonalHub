package com.gernalix.luoghi.capsules.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.data.PlaceDao
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class PlaceGeofenceRegistrar(
    private val context: Context,
    private val dao: PlaceDao,
) {
    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getGeofencingClient(appContext) }

    suspend fun reconcile(): PlaceGeofenceResult {
        if (!hasBackgroundLocationPermission(appContext)) return PlaceGeofenceResult.PermissionMissing
        val configs = dao.enabledGeofenceConfigs()
        val places = dao.placesByUuids(configs.map { it.placeUuid }).associateBy { it.uuid }
        val geofences = configs.mapNotNull { config ->
            val place = places[config.placeUuid] ?: return@mapNotNull null
            val lat = place.lat ?: return@mapNotNull null
            val lon = place.lon ?: return@mapNotNull null
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
        runCatching { removeGeofences() }
        if (geofences.isEmpty()) return PlaceGeofenceResult.Success
        return runCatching {
            addGeofences(geofences)
            PlaceGeofenceResult.Success
        }.getOrElse { PlaceGeofenceResult.PermissionMissing }
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

    private suspend fun removeGeofences() {
        client.removeGeofences(pendingIntent()).asUnit()
    }

    private fun pendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(appContext, PlaceGeofenceReceiver::class.java).apply {
            action = PlaceGeofenceReceiver.ACTION_GEOFENCE_TRANSITION
            data = Uri.parse("places://geofence/transitions")
        }
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    companion object {
        fun hasBackgroundLocationPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
    }
}

private suspend fun com.google.android.gms.tasks.Task<Void>.asUnit(): Unit =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(Unit) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.resumeWithException(java.util.concurrent.CancellationException()) }
    }
