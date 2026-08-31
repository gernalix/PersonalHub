package com.gernalix.luoghi.capsules.routedistance

import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.data.PlaceDao
import com.gernalix.luoghi.data.DatabaseMutationCoordinator
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.data.RouteDistanceCacheEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

class RouteDistanceRepository(
    private val dao: PlaceDao,
    private val googleRoutesClient: GoogleRoutesClient,
    private val onCacheChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<RouteCacheKey, CompletableDeferred<RouteDistanceResult>>()

    suspend fun getDistance(
        origin: PlaceEntity,
        destination: PlaceEntity,
        mode: RouteTravelMode = RouteTravelMode.BICYCLE,
    ): RouteDistanceResult {
        if (origin.uuid == destination.uuid) return RouteDistanceResult.zero()
        val key = RouteCacheKey(
            originPlaceId = origin.uuid,
            destinationPlaceId = destination.uuid,
            travelMode = mode.cacheValue,
            provider = RouteDistanceProviders.GOOGLE_ROUTES,
        )
        var owner = false
        val deferred = inFlightMutex.withLock {
            inFlight[key]?.let { return@withLock it }
            owner = true
            CompletableDeferred<RouteDistanceResult>().also { inFlight[key] = it }
        }
        if (!owner) return deferred.await()

        return try {
            val result = resolveDistance(origin, destination, mode)
            deferred.complete(result)
            result
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            inFlightMutex.withLock {
                if (inFlight[key] === deferred) inFlight.remove(key)
            }
        }
    }

    suspend fun distanceStats(
        places: List<PlaceEntity>,
        events: List<PlaceEventEntity>,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RouteDistanceStatsUi {
        val now = Instant.ofEpochMilli(nowMs)
        return RouteDistanceStatsUi(
            today = distanceForPeriod(places, events, todayPeriod(now, zoneId)),
            week = distanceForPeriod(places, events, weekPeriod(now, zoneId)),
            month = distanceForPeriod(places, events, monthPeriod(now, zoneId)),
            loading = false,
        )
    }

    suspend fun distanceForPeriod(
        places: List<PlaceEntity>,
        events: List<PlaceEventEntity>,
        period: RouteDistancePeriod,
        mode: RouteTravelMode = RouteTravelMode.BICYCLE,
    ): RouteDistancePeriodUi {
        val placesByUuid = places.associateBy { it.uuid }
        val visits = events
            .asSequence()
            .filter { it.eventType == PlaceEventTypes.CHECK_IN }
            .sortedWith(compareBy<PlaceEventEntity> { it.timestamp }.thenBy { it.id })
            .map { CheckInVisit(placeId = it.placeId, timestamp = it.timestamp) }
            .toList()

        var distanceMeters = 0L
        var countedSegments = 0
        var googleSegments = 0
        var fallbackSegments = 0
        var unavailableSegments = 0

        visits.zipWithNext().forEach { (originVisit, destinationVisit) ->
            if (originVisit.placeId == destinationVisit.placeId) return@forEach
            if (!period.contains(destinationVisit.timestamp)) return@forEach
            val origin = placesByUuid[originVisit.placeId]
            val destination = placesByUuid[destinationVisit.placeId]
            if (origin == null || destination == null) {
                unavailableSegments++
                return@forEach
            }
            val result = getDistance(origin, destination, mode)
            if (!result.counted) {
                if (result.method == RouteDistanceMethod.UNAVAILABLE) unavailableSegments++
                return@forEach
            }
            distanceMeters += result.distanceMeters
            countedSegments++
            when {
                result.usesGoogle -> googleSegments++
                result.usesFallback -> fallbackSegments++
            }
        }

        return RouteDistancePeriodUi(
            distanceMeters = distanceMeters,
            method = aggregateMethod(
                countedSegments = countedSegments,
                googleSegments = googleSegments,
                fallbackSegments = fallbackSegments,
                unavailableSegments = unavailableSegments,
            ),
            segmentCount = countedSegments,
            fallbackSegmentCount = fallbackSegments,
            unavailableSegmentCount = unavailableSegments,
        )
    }

    private suspend fun resolveDistance(
        origin: PlaceEntity,
        destination: PlaceEntity,
        mode: RouteTravelMode,
    ): RouteDistanceResult {
        validCache(origin, destination, mode)?.let { cache ->
            return cache.asGoogleResult(RouteDistanceMethod.GOOGLE_BICYCLE)
        }

        val originCoordinates = origin.routeCoordinates()
        val destinationCoordinates = destination.routeCoordinates()
        if (originCoordinates == null || destinationCoordinates == null) return RouteDistanceResult.unavailable()

        if (googleRoutesClient.isConfigured) {
            googleRoutesClient.bicycleRoute(originCoordinates, destinationCoordinates)?.let { googleDistance ->
                saveGoogleCache(origin, destination, mode, googleDistance)
                return RouteDistanceResult(
                    distanceMeters = googleDistance.distanceMeters,
                    durationSeconds = googleDistance.durationSeconds,
                    method = RouteDistanceMethod.GOOGLE_BICYCLE,
                )
            }
        }

        validCache(destination, origin, mode)?.let { inverseCache ->
            return inverseCache.asGoogleResult(RouteDistanceMethod.INVERSE_GOOGLE_BICYCLE)
        }

        return RouteDistanceResult(
            distanceMeters = RouteDistanceMath.haversineMeters(originCoordinates, destinationCoordinates),
            durationSeconds = null,
            method = RouteDistanceMethod.HAVERSINE_FALLBACK,
        )
    }

    private suspend fun validCache(
        origin: PlaceEntity,
        destination: PlaceEntity,
        mode: RouteTravelMode,
    ): RouteDistanceCacheEntity? {
        val cache = dao.getRouteDistanceCache(
            originPlaceId = origin.uuid,
            destinationPlaceId = destination.uuid,
            travelMode = mode.cacheValue,
            provider = RouteDistanceProviders.GOOGLE_ROUTES,
        ) ?: return null
        return cache.takeIf { it.hasValidSnapshots(origin, destination) }
    }

    private suspend fun saveGoogleCache(
        origin: PlaceEntity,
        destination: PlaceEntity,
        mode: RouteTravelMode,
        distance: GoogleRouteDistance,
    ) {
        DatabaseMutationCoordinator.mutex.withLock {
            dao.insertRouteDistanceCache(
                RouteDistanceCacheEntity(
                    originPlaceId = origin.uuid,
                    destinationPlaceId = destination.uuid,
                    travelMode = mode.cacheValue,
                    provider = RouteDistanceProviders.GOOGLE_ROUTES,
                    distanceMeters = distance.distanceMeters,
                    durationSeconds = distance.durationSeconds,
                    computedAt = clock(),
                    originLatitudeSnapshot = origin.lat,
                    originLongitudeSnapshot = origin.lon,
                    destinationLatitudeSnapshot = destination.lat,
                    destinationLongitudeSnapshot = destination.lon,
                    originVersion = origin.updatedAt,
                    destinationVersion = destination.updatedAt,
                    metadata = "routes_api_v2_bicycle",
                )
            )
        }
        onCacheChanged()
    }

    private fun RouteDistanceCacheEntity.asGoogleResult(method: RouteDistanceMethod): RouteDistanceResult =
        RouteDistanceResult(
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            method = method,
        )

    private fun RouteDistanceCacheEntity.hasValidSnapshots(origin: PlaceEntity, destination: PlaceEntity): Boolean {
        val originSnapshot = snapshotCoordinates(originLatitudeSnapshot, originLongitudeSnapshot) ?: return false
        val destinationSnapshot = snapshotCoordinates(destinationLatitudeSnapshot, destinationLongitudeSnapshot) ?: return false
        val currentOrigin = origin.routeCoordinates() ?: return false
        val currentDestination = destination.routeCoordinates() ?: return false
        return RouteDistanceMath.haversineMeters(originSnapshot, currentOrigin) <= CACHE_COORDINATE_TOLERANCE_M &&
            RouteDistanceMath.haversineMeters(destinationSnapshot, currentDestination) <= CACHE_COORDINATE_TOLERANCE_M
    }

    private fun PlaceEntity.routeCoordinates(): RouteCoordinates? {
        val latitude = lat?.takeIf { it.isFinite() } ?: return null
        val longitude = lon?.takeIf { it.isFinite() } ?: return null
        return RouteCoordinates(latitude, longitude)
    }

    private fun snapshotCoordinates(latitude: Double?, longitude: Double?): RouteCoordinates? {
        val lat = latitude?.takeIf { it.isFinite() } ?: return null
        val lon = longitude?.takeIf { it.isFinite() } ?: return null
        return RouteCoordinates(lat, lon)
    }

    private fun RouteDistancePeriod.contains(timestampMs: Long): Boolean {
        val timestamp = Instant.ofEpochMilli(timestampMs)
        return !timestamp.isBefore(startInclusive) && timestamp.isBefore(endExclusive)
    }

    private fun todayPeriod(now: Instant, zoneId: ZoneId): RouteDistancePeriod {
        val localNow = now.atZone(zoneId)
        val start = localNow.toLocalDate().atStartOfDay(zoneId).toInstant()
        return RouteDistancePeriod(start, now)
    }

    private fun weekPeriod(now: Instant, zoneId: ZoneId): RouteDistancePeriod {
        val localNow = now.atZone(zoneId)
        val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val startDate = localNow.toLocalDate().with(TemporalAdjusters.previousOrSame(firstDay))
        return RouteDistancePeriod(startDate.atStartOfDay(zoneId).toInstant(), now)
    }

    private fun monthPeriod(now: Instant, zoneId: ZoneId): RouteDistancePeriod {
        val localNow = now.atZone(zoneId)
        val startDate = localNow.toLocalDate().withDayOfMonth(1)
        return RouteDistancePeriod(startDate.atStartOfDay(zoneId).toInstant(), now)
    }

    private fun aggregateMethod(
        countedSegments: Int,
        googleSegments: Int,
        fallbackSegments: Int,
        unavailableSegments: Int,
    ): RouteDistanceMethod =
        when {
            countedSegments == 0 && unavailableSegments > 0 -> RouteDistanceMethod.UNAVAILABLE
            countedSegments == 0 -> RouteDistanceMethod.ZERO
            fallbackSegments == 0 && unavailableSegments == 0 && googleSegments == countedSegments -> RouteDistanceMethod.GOOGLE_BICYCLE
            googleSegments == 0 -> RouteDistanceMethod.HAVERSINE_FALLBACK
            else -> RouteDistanceMethod.MIXED
        }

    private data class RouteCacheKey(
        val originPlaceId: String,
        val destinationPlaceId: String,
        val travelMode: String,
        val provider: String,
    )

    private data class CheckInVisit(
        val placeId: String,
        val timestamp: Long,
    )

    private companion object {
        const val CACHE_COORDINATE_TOLERANCE_M = 1L
    }
}
