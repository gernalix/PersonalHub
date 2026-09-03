package com.gernalix.luoghi

import android.content.Context
import com.gernalix.luoghi.capsules.addressautocomplete.AddressAutocompleteRepository
import com.gernalix.luoghi.capsules.addressautocomplete.AddressAutocompleteSource
import com.gernalix.luoghi.capsules.aliases.AliasesCapsule
import com.gernalix.luoghi.capsules.checkin.CheckInCapsule
import com.gernalix.luoghi.capsules.links.LinksCapsule
import com.gernalix.luoghi.capsules.location.FusedLocationCapsule
import com.gernalix.luoghi.capsules.location.LocationSource
import com.gernalix.luoghi.capsules.places.PlacesCapsule
import com.gernalix.luoghi.capsules.routedistance.HttpGoogleRoutesClient
import com.gernalix.luoghi.capsules.routedistance.RouteDistanceCapsule
import com.gernalix.luoghi.capsules.routedistance.RouteDistanceRepository
import com.gernalix.luoghi.capsules.safexport.PersistentMutationTracker
import com.gernalix.luoghi.capsules.safexport.SafExportCapsule
import com.gernalix.luoghi.capsules.stats.StatsCapsule
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.luoghi.backup.RestoreCoordinator

class LuoghiAppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = LuoghiDatabase.get(appContext)
    private val dao = database.placeDao()
    private val placeRepository = PlaceRepository(appContext, database, dao)

    val places = PlacesCapsule(placeRepository)
    val aliases = AliasesCapsule(placeRepository)
    val links = LinksCapsule(placeRepository)
    val checkIns = CheckInCapsule(placeRepository)
    val stats = StatsCapsule(placeRepository)
    val routeDistances by lazy {
        RouteDistanceCapsule(
        RouteDistanceRepository(
            dao = dao,
            googleRoutesClient = HttpGoogleRoutesClient(),
            onCacheChanged = { PersistentMutationTracker.record(appContext, "route_distance_cache.upsert") },
        )
        )
    }
    val location: LocationSource by lazy { FusedLocationCapsule(appContext) }
    val safExport by lazy { SafExportCapsule(appContext) }
    val restore by lazy { RestoreCoordinator(appContext, database) }
    val addressAutocomplete: AddressAutocompleteSource by lazy { AddressAutocompleteRepository(appContext) }
}
