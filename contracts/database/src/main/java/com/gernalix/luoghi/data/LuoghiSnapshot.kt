package com.gernalix.luoghi.data

data class LuoghiSnapshot(
    val places: List<PlaceEntity>,
    val aliases: List<PlaceAliasEntity>,
    val links: List<PlaceLinkEntity>,
    val events: List<PlaceEventEntity>,
    val globalStats: List<GlobalStatsStateEntity>,
    val routeDistanceCache: List<RouteDistanceCacheEntity>,
    val historyAuditLog: List<HistoryAuditLogEntity>,
    val historyActions: List<HistoryActionEntity>,
    val geofenceConfigs: List<PlaceGeofenceConfigEntity> = emptyList(),
    val geofenceTransitionLog: List<PlaceGeofenceTransitionLogEntity> = emptyList(),
) {
    val tableCounts: Map<String, Int>
        get() = linkedMapOf(
            "places" to places.size,
            "place_aliases" to aliases.size,
            "place_links" to links.size,
            "place_events" to events.size,
            "global_stats_state" to globalStats.size,
            "route_distance_cache" to routeDistanceCache.size,
            "history_audit_log" to historyAuditLog.size,
            "history_actions" to historyActions.size,
            "place_geofence_configs" to geofenceConfigs.size,
            "place_geofence_transition_log" to geofenceTransitionLog.size,
        )

    val isEmpty: Boolean
        get() = tableCounts.values.all { it == 0 }
}
