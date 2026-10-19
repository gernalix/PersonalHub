package com.gernalix.luoghi.capsules.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gernalix.luoghi.data.PlaceGeofenceTransitionLogEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlaceGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_GEOFENCE_TRANSITION -> handlePlatformEvent(context, intent)
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    ACTION_RESTORE,
                    -> restore(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handlePlatformEvent(context: Context, intent: Intent) {
        if (!isForActiveProfile(context, intent)) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val transition = PlaceGeofenceTransition.fromGeofenceTransition(event.geofenceTransition) ?: return
        handleTransitions(context, event.triggeringGeofences.orEmpty().map { it.requestId },
            transition, System.currentTimeMillis())
    }

    private suspend fun restore(context: Context) {
        val db = PersonalHubDatabase.get(context.applicationContext)
        PlaceGeofenceRegistrar(context.applicationContext, db.placeDao()).reconcile()
    }

    companion object {
        internal fun isForActiveProfile(context: Context, intent: Intent): Boolean =
            intent.getStringExtra(PlaceGeofenceRegistrar.PROFILE_ID) == DatabaseProfiles.activeProfileId(context)

        const val ACTION_GEOFENCE_TRANSITION = "com.gernalix.luoghi.action.GEOFENCE_TRANSITION"
        const val ACTION_RESTORE = "com.gernalix.luoghi.action.GEOFENCE_RESTORE"
        private const val DEDUPE_WINDOW_MS = 2 * 60 * 1_000L

        /** Notify every area; overlapping arrivals must not manufacture multiple visits. */
        suspend fun handleTransitions(
            context: Context,
            placeUuids: Collection<String>,
            transition: PlaceGeofenceTransition,
            nowMs: Long,
        ): Map<String, PlaceGeofenceResult> {
            val dao = PersonalHubDatabase.get(context.applicationContext).placeDao()
            val eligible = placeUuids.distinct().filter { id ->
                val config = dao.geofenceConfig(id)
                val place = dao.getPlace(id)
                val lat = place?.lat
                val lon = place?.lon
                config != null && config.enabled && place != null && !place.archived &&
                    lat != null && lon != null && com.gernalix.luoghi.capsules.mapviewer.OsmAndMapPlan.valid(lat, lon) &&
                    when (transition) {
                        PlaceGeofenceTransition.ENTER -> config.enterEnabled
                        PlaceGeofenceTransition.EXIT -> config.exitEnabled
                    }
            }
            val activePlace = if (transition == PlaceGeofenceTransition.EXIT) dao.getActiveVisitEvent()?.placeId else null
            val results = eligible.associateWith { id ->
                handleTransition(context, id, transition, nowMs,
                    allowAutomaticVisit = eligible.size == 1 || id == activePlace)
            }
            if (eligible.size > 1 && transition == PlaceGeofenceTransition.ENTER &&
                results.values.any { it == PlaceGeofenceResult.Success }) {
                PlaceGeofenceNotifier.notifyAmbiguous(context, eligible.size)
            }
            return results
        }

        suspend fun handleTransition(
            context: Context,
            placeUuid: String,
            transition: PlaceGeofenceTransition,
            nowMs: Long,
            allowAutomaticVisit: Boolean = true,
        ): PlaceGeofenceResult {
            val db = PersonalHubDatabase.get(context.applicationContext)
            val dao = db.placeDao()
            val config = dao.geofenceConfig(placeUuid) ?: return PlaceGeofenceResult.PlaceMissing
            val enabled = when (transition) {
                PlaceGeofenceTransition.ENTER -> config.enabled && config.enterEnabled
                PlaceGeofenceTransition.EXIT -> config.enabled && config.exitEnabled
            }
            if (!enabled) return PlaceGeofenceResult.PlaceMissing
            val place = dao.getPlace(placeUuid)?.takeUnless { it.archived } ?: return PlaceGeofenceResult.PlaceMissing
            val lat = place.lat
            val lon = place.lon
            if (lat == null || lon == null || !com.gernalix.luoghi.capsules.mapviewer.OsmAndMapPlan.valid(lat, lon)) return PlaceGeofenceResult.CoordinatesMissing
            val bucket = nowMs / DEDUPE_WINDOW_MS
            val inserted = dao.insertGeofenceTransitionLog(
                PlaceGeofenceTransitionLogEntity(
                    placeUuid = placeUuid,
                    transition = transition.storageName,
                    bucket = bucket,
                    createdAt = nowMs,
                )
            )
            if (inserted <= 0L) return PlaceGeofenceResult.DuplicateTransition
            val action = when (transition) {
                PlaceGeofenceTransition.ENTER -> PlaceGeofenceAction.parse(config.enterAction)
                PlaceGeofenceTransition.EXIT -> PlaceGeofenceAction.parse(config.exitAction)
            }
            val repository = PlaceRepository(context.applicationContext, db, dao)
            val recorded = if (action == PlaceGeofenceAction.AUTOMATIC_VISIT && allowAutomaticVisit) {
                val result = when (transition) {
                    PlaceGeofenceTransition.ENTER -> repository.recordManualCheckIn(
                        placeUuid = placeUuid,
                        timestamp = nowMs,
                        source = "Luoghi geofence",
                    )
                    PlaceGeofenceTransition.EXIT -> repository.closeCanonicalVisit(
                        placeUuid = placeUuid,
                        timestamp = nowMs,
                        source = "Luoghi geofence",
                    )
                }
                if (result is com.gernalix.luoghi.capsules.checkin.HistoryMutationResult.Failure) {
                    // Failed writes must be retryable once the conflicting visit is resolved.
                    dao.deleteGeofenceTransitionLog(inserted)
                    return PlaceGeofenceResult.VisitRejected(result.error.name)
                }
                true
            } else {
                false
            }
            when (transition) {
                PlaceGeofenceTransition.ENTER -> dao.markGeofenceEnter(placeUuid, nowMs)
                PlaceGeofenceTransition.EXIT -> dao.markGeofenceExit(placeUuid, nowMs)
            }
            PlaceGeofenceNotifier.notifyTransition(context, place, transition, recorded)
            return PlaceGeofenceResult.Success
        }
    }
}
