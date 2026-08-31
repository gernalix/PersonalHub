package com.gernalix.luoghi.capsules.routedistance

import com.gernalix.luoghi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

interface GoogleRoutesClient {
    val isConfigured: Boolean

    suspend fun bicycleRoute(
        origin: RouteCoordinates,
        destination: RouteCoordinates,
    ): GoogleRouteDistance?
}

class HttpGoogleRoutesClient(
    private val apiKey: String = BuildConfig.GOOGLE_ROUTES_API_KEY,
) : GoogleRoutesClient {
    override val isConfigured: Boolean = apiKey.isNotBlank()

    override suspend fun bicycleRoute(
        origin: RouteCoordinates,
        destination: RouteCoordinates,
    ): GoogleRouteDistance? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val connection = (URL(ROUTES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            setRequestProperty("X-Goog-FieldMask", "routes.distanceMeters,routes.duration")
        }
        try {
            val body = routeRequestBody(origin, destination).toByteArray(Charsets.UTF_8)
            connection.outputStream.use { output ->
                output.write(body)
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@withContext null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            parseRouteResponse(response)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun routeRequestBody(origin: RouteCoordinates, destination: RouteCoordinates): String =
        JSONObject()
            .put("origin", waypoint(origin))
            .put("destination", waypoint(destination))
            .put("travelMode", RouteTravelMode.BICYCLE.routesApiValue)
            .toString()

    private fun waypoint(coordinates: RouteCoordinates): JSONObject =
        JSONObject()
            .put(
                "location",
                JSONObject()
                    .put(
                        "latLng",
                        JSONObject()
                            .put("latitude", coordinates.latitude)
                            .put("longitude", coordinates.longitude)
                    )
            )

    private fun parseRouteResponse(response: String): GoogleRouteDistance? {
        val route = JSONObject(response)
            .optJSONArray("routes")
            ?.optJSONObject(0)
            ?: return null
        val distanceMeters = route.optLong("distanceMeters", -1L)
        val durationSeconds = parseDurationSeconds(route.optString("duration"))
        if (distanceMeters < 0L || durationSeconds == null) return null
        return GoogleRouteDistance(
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
        )
    }

    private fun parseDurationSeconds(value: String): Long? {
        val seconds = value.removeSuffix("s").toDoubleOrNull() ?: return null
        return seconds.toLong().coerceAtLeast(0L)
    }

    private companion object {
        const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
        const val TIMEOUT_MS = 5_000
    }
}
