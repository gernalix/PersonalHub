package com.gernalix.luoghi.capsules.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.PlaceGeofenceTransitionLogEntity
import com.gernalix.luoghi.data.PlaceRepository
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
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val transition = PlaceGeofenceTransition.fromGeofenceTransition(event.geofenceTransition) ?: return
        val triggering = event.triggeringGeofences.orEmpty()
        if (triggering.size != 1) {
            PlaceGeofenceNotifier.notifyAmbiguous(context, triggering.size)
            return
        }
        handleTransition(context, triggering.single().requestId, transition, System.currentTimeMillis())
    }

    private suspend fun restore(context: Context) {
        val db = LuoghiDatabase.get(context.applicationContext)
        PlaceGeofenceRegistrar(context.applicationContext, db.placeDao()).reconcile()
    }

    companion object {
        const val ACTION_GEOFENCE_TRANSITION = "com.gernalix.luoghi.action.GEOFENCE_TRANSITION"
        const val ACTION_RESTORE = "com.gernalix.luoghi.action.GEOFENCE_RESTORE"
        private const val DEDUPE_WINDOW_MS = 2 * 60 * 1_000L

        suspend fun handleTransition(
            context: Context,
            placeUuid: String,
            transition: PlaceGeofenceTransition,
            nowMs: Long,
        ): PlaceGeofenceResult {
            val db = LuoghiDatabase.get(context.applicationContext)
            val dao = db.placeDao()
            val config = dao.geofenceConfig(placeUuid) ?: return PlaceGeofenceResult.PlaceMissing
            val enabled = when (transition) {
                PlaceGeofenceTransition.ENTER -> config.enabled && config.enterEnabled
                PlaceGeofenceTransition.EXIT -> config.enabled && config.exitEnabled
            }
            if (!enabled) return PlaceGeofenceResult.PlaceMissing
            val place = dao.getPlace(placeUuid) ?: return PlaceGeofenceResult.PlaceMissing
            if (place.lat == null || place.lon == null) return PlaceGeofenceResult.CoordinatesMissing
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
            val recorded = if (action == PlaceGeofenceAction.AUTOMATIC_VISIT) {
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
