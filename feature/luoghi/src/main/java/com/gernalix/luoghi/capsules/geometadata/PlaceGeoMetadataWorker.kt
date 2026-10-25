package com.gernalix.luoghi.capsules.geometadata

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gernalix.luoghi.data.PlaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Fetches once per saved coordinate pair; all subsequent Luoghi searches use local SQLite. */
class PlaceGeoMetadataWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val repo = PlaceRepository(applicationContext)
        val startMs = SystemClock.elapsedRealtime()
        // WorkManager has a finite execution window. Process small batches on a single thread.
        while (!isStopped && SystemClock.elapsedRealtime() - startMs < 7 * 60_000L) {
            val place = repo.placesNeedingGeoMetadata(1).firstOrNull() ?: return Result.success()
            val lat = place.lat ?: return Result.failure()
            val lon = place.lon ?: return Result.failure()
            val metadata = try {
                when (val response = NominatimReverse.fetch(applicationContext, lat, lon)) {
                    is NominatimReverse.Response.Found -> PlaceGeoMetadataCodec.found(place, response.json)
                    NominatimReverse.Response.NoMatch -> PlaceGeoMetadataCodec.noMatch(place)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Transient HTTP failures/connection loss must not prevent saving the place.
                return Result.retry()
            }
            repo.saveGeoMetadataIfUnchanged(metadata)
        }
        return if (isStopped) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "places-geo-metadata"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PlaceGeoMetadataWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request,
            )
        }
    }
}

/** Official public endpoint is configurable independently of the APK. */
internal object NominatimReverse {
    sealed interface Response {
        data class Found(val json: String) : Response
        data object NoMatch : Response
    }

    private val requests = Mutex()
    private var lastRequestMs = 0L

    suspend fun fetch(context: Context, lat: Double, lon: Double): Response = requests.withLock {
        // Application-wide 1 request/s ceiling required by the public Nominatim usage policy.
        val waitMs = 1_100L - (SystemClock.elapsedRealtime() - lastRequestMs)
        if (lastRequestMs != 0L && waitMs > 0) delay(waitMs)
        lastRequestMs = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            val baseUrl = context.getSharedPreferences("places_geocoding", Context.MODE_PRIVATE)
                .getString("reverse_url", null)
                ?.takeIf { it.startsWith("https://") }
                ?: "https://nominatim.openstreetmap.org/reverse"
            val url = URL("$baseUrl?format=jsonv2&addressdetails=1&extratags=1&namedetails=1&lat=$lat&lon=$lon")
            val conn = (url.openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "PersonalHub/1.0 (https://github.com/gernalix/PersonalHub)")
                conn.setRequestProperty("Accept", "application/json")
                when (conn.responseCode) {
                    200 -> Response.Found(conn.inputStream.bufferedReader().use { it.readText() })
                    404 -> Response.NoMatch
                    else -> error("Reverse-geocoding HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
