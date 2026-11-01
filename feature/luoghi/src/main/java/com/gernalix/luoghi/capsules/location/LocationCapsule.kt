package com.gernalix.luoghi.capsules.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    val capturedAt: Long = System.currentTimeMillis(),
)

interface LocationSource {
    suspend fun currentLocation(): LocationSample?
}

class FusedLocationCapsule(
    private val context: Context,
) : LocationSource {
    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    override suspend fun currentLocation(): LocationSample? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission(appContext)) return@withContext null
        resolveLocationWithFallback(::currentFusedLocation, ::lastKnownLocation)
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFusedLocation(): LocationSample? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellation.cancel() }
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener { location -> continuation.resume(location?.toSample()) }
            .addOnFailureListener { continuation.resume(null) }
            .addOnCanceledListener { continuation.resume(null) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnownLocation(): LocationSample? = suspendCancellableCoroutine { continuation ->
        client.lastLocation
            .addOnSuccessListener { location -> continuation.resume(location?.toSample()) }
            .addOnFailureListener { continuation.resume(null) }
            .addOnCanceledListener { continuation.resume(null) }
    }

    private fun Location.toSample(): LocationSample =
        LocationSample(
            latitude = latitude,
            longitude = longitude,
            accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
            capturedAt = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

/** A slow fresh fix must not consume the separate cached-fix fallback budget. */
internal suspend fun resolveLocationWithFallback(
    current: suspend () -> LocationSample?,
    lastKnown: suspend () -> LocationSample?,
    currentTimeoutMs: Long = 6_000L,
    fallbackTimeoutMs: Long = 1_000L,
    now: () -> Long = System::currentTimeMillis,
): LocationSample? {
    val fresh = withTimeoutOrNull(currentTimeoutMs) { current() }
    if (fresh != null && fresh.isUsable(now())) return fresh
    return withTimeoutOrNull(fallbackTimeoutMs) { lastKnown() }?.takeIf { it.isUsable(now()) }
}

private fun LocationSample.isUsable(nowMs: Long): Boolean =
    latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        capturedAt > 0L && (nowMs - capturedAt) in 0L..120_000L &&
        (accuracyM == null || (accuracyM.isFinite() && accuracyM > 0.0 && accuracyM <= 50.0))
