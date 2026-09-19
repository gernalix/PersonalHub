package com.gernalix.luoghi.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.gernalix.luoghi.capsules.places.GeoSearch
import com.gernalix.personalhub.core.database.HubAutoExport
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.luoghi.data.PlaceDao
import com.gernalix.luoghi.data.DatabaseMutationCoordinator
import com.gernalix.luoghi.data.PlaceAliasEntity
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.data.PlaceLinkEntity
import com.gernalix.luoghi.data.PlaceRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

class PlacesProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(PlacesContract.AUTHORITY, PlacesContract.Places.PATH, PLACES)
        addURI(PlacesContract.AUTHORITY, "${PlacesContract.Places.PATH}/*", PLACE_BY_UUID)
        addURI(PlacesContract.AUTHORITY, PlacesContract.Nicknames.PATH, NICKNAMES)
        addURI(PlacesContract.AUTHORITY, PlacesContract.Nearby.PATH, NEARBY)
        addURI(PlacesContract.AUTHORITY, PlacesContract.Nearest.PATH, NEAREST)
        addURI(PlacesContract.AUTHORITY, PlacesContract.Links.PATH, LINKS)
        addURI(PlacesContract.AUTHORITY, "${PlacesContract.Links.PATH}/#", LINK_BY_ID)
        addURI(PlacesContract.AUTHORITY, PlacesContract.Aliases.PATH, ALIASES)
        addURI(PlacesContract.AUTHORITY, "${PlacesContract.Aliases.PATH}/#", ALIAS_BY_ID)
        addURI(PlacesContract.AUTHORITY, PlacesContract.Events.PATH, EVENTS)
        addURI(PlacesContract.AUTHORITY, "${PlacesContract.Events.PATH}/#", EVENT_BY_ID)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val places = providerIo {
            val dao = PersonalHubDatabase.get(requireNotNull(context)).placeDao()
            when (matcher.match(uri)) {
                PLACES -> dao.listPlacesBlocking()
                PLACE_BY_UUID -> listOfNotNull(dao.getPlaceBlocking(uri.lastPathSegment.orEmpty()))
                NICKNAMES -> dao.listPlacesBlocking().toNicknameCursor()
                NEARBY -> nearbyPlaces(dao, uri)
                NEAREST -> nearestPlaces(dao, uri)
                LINKS -> dao.listLinksBlocking().toLinkCursor()
                LINK_BY_ID -> listOfNotNull(dao.getLinkBlocking(uri.lastPathSegment.orEmpty().toLongOrNull() ?: -1L)).toLinkCursor()
                ALIASES -> dao.listAliasesBlocking().toAliasCursor()
                ALIAS_BY_ID -> listOfNotNull(dao.getAliasBlocking(uri.lastPathSegment.orEmpty().toLongOrNull() ?: -1L)).toAliasCursor()
                EVENTS -> dao.listEventsBlocking().toEventCursor()
                EVENT_BY_ID -> listOfNotNull(dao.getEventBlocking(uri.lastPathSegment.orEmpty().toLongOrNull() ?: -1L)).toEventCursor()
                else -> throw IllegalArgumentException("Unsupported query URI: $uri")
            }
        }
        return when (places) {
            is Cursor -> places
            else -> @Suppress("UNCHECKED_CAST") (places as List<PlaceEntity>).toCursor()
        }
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        PLACES, NEARBY, NEAREST -> "vnd.android.cursor.dir/vnd.com.gernalix.luoghi.place"
        NICKNAMES -> "vnd.android.cursor.dir/vnd.com.gernalix.luoghi.nickname"
        PLACE_BY_UUID -> "vnd.android.cursor.item/vnd.com.gernalix.luoghi.place"
        LINKS -> "vnd.android.cursor.dir/vnd.com.gernalix.luoghi.place_link"
        LINK_BY_ID -> "vnd.android.cursor.item/vnd.com.gernalix.luoghi.place_link"
        ALIASES -> "vnd.android.cursor.dir/vnd.com.gernalix.luoghi.place_alias"
        ALIAS_BY_ID -> "vnd.android.cursor.item/vnd.com.gernalix.luoghi.place_alias"
        EVENTS -> "vnd.android.cursor.dir/vnd.com.gernalix.luoghi.place_event"
        EVENT_BY_ID -> "vnd.android.cursor.item/vnd.com.gernalix.luoghi.place_event"
        else -> throw IllegalArgumentException("Unsupported URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val v = requireNotNull(values) { "ContentValues required" }
        val match = matcher.match(uri)
        return providerIo {
            val dao = PersonalHubDatabase.get(requireNotNull(context)).placeDao()
            if (match == EVENTS) {
                val now = System.currentTimeMillis()
                val id = PlaceRepository(requireNotNull(context)).recordPlaceEventEntity(
                    event = v.toEvent(now),
                    mutationSource = mutationSource(match, "insert"),
                )
                ContentUris.withAppendedId(PlacesContract.Events.CONTENT_URI, id)
            } else DatabaseMutationCoordinator.mutex.withLock {
                when (match) {
                PLACES -> {
                    val now = System.currentTimeMillis()
                    val uuid = v.getAsString(PlacesContract.Places.UUID)
                        ?.takeIf { isUuid(it) }
                        ?: UUID.randomUUID().toString()
                    dao.upsertPlaceBlocking(v.toPlace(uuid, now))
                    PlacesContract.Places.CONTENT_URI.buildUpon().appendPath(uuid).build()
                }
                LINKS -> {
                    val now = System.currentTimeMillis()
                    val id = dao.insertLinkBlocking(
                        PlaceLinkEntity(
                            placeUuid = v.getAsString(PlacesContract.Links.PLACE_UUID),
                            ownerApp = v.getAsString(PlacesContract.Links.OWNER_APP),
                            ownerType = v.getAsString(PlacesContract.Links.OWNER_TYPE),
                            ownerId = v.getAsString(PlacesContract.Links.OWNER_ID),
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                    ContentUris.withAppendedId(PlacesContract.Links.CONTENT_URI, id)
                }
                ALIASES -> {
                    val now = System.currentTimeMillis()
                    val id = dao.insertAliasBlocking(
                        PlaceAliasEntity(
                            placeUuid = v.getAsString(PlacesContract.Aliases.PLACE_UUID),
                            alias = v.getAsString(PlacesContract.Aliases.ALIAS),
                            appScope = v.getAsString(PlacesContract.Aliases.APP_SCOPE),
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                    ContentUris.withAppendedId(PlacesContract.Aliases.CONTENT_URI, id)
                }
                else -> throw IllegalArgumentException("Unsupported insert URI: $uri")
                }
            }
        }.also {
            if (match != EVENTS) {
                context?.let { appContext -> HubAutoExport.request(appContext) }
            }
            context?.contentResolver?.notifyChange(PlacesContract.Places.CONTENT_URI, null)
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val match = matcher.match(uri)
        val uuid = when (match) {
            PLACE_BY_UUID -> uri.lastPathSegment.orEmpty()
            LINK_BY_ID, ALIAS_BY_ID -> ""
            else -> throw IllegalArgumentException("Unsupported update URI: $uri")
        }
        val v = requireNotNull(values)
        val updated = providerIo {
            DatabaseMutationCoordinator.mutex.withLock {
                val dao = PersonalHubDatabase.get(requireNotNull(context)).placeDao()
                when (match) {
                PLACE_BY_UUID -> {
                    val existing = dao.getPlaceBlocking(uuid) ?: return@providerIo 0
                    dao.upsertPlaceBlocking(
                        existing.copy(
                            nickname = v.getAsString(PlacesContract.Places.NICKNAME) ?: existing.nickname,
                            address = v.getAsString(PlacesContract.Places.ADDRESS) ?: existing.address,
                            lat = v.getAsDoubleOrNull(PlacesContract.Places.LAT) ?: existing.lat,
                            lon = v.getAsDoubleOrNull(PlacesContract.Places.LON) ?: existing.lon,
                            radiusM = v.getAsDoubleOrNull(PlacesContract.Places.RADIUS_M) ?: existing.radiusM,
                            notes = v.getAsString(PlacesContract.Places.NOTES) ?: existing.notes,
                            sourceApp = v.getAsString(PlacesContract.Places.SOURCE_APP) ?: existing.sourceApp,
                            archived = v.getAsBooleanOrNull(PlacesContract.Places.ARCHIVED) ?: existing.archived,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    1
                }
                LINK_BY_ID -> {
                    val id = uri.lastPathSegment.orEmpty().toLongOrNull() ?: return@providerIo 0
                    val existing = dao.getLinkBlocking(id) ?: return@providerIo 0
                    dao.insertLinkBlocking(
                        existing.copy(
                            placeUuid = v.getAsString(PlacesContract.Links.PLACE_UUID) ?: existing.placeUuid,
                            ownerApp = v.getAsString(PlacesContract.Links.OWNER_APP) ?: existing.ownerApp,
                            ownerType = v.getAsString(PlacesContract.Links.OWNER_TYPE) ?: existing.ownerType,
                            ownerId = v.getAsString(PlacesContract.Links.OWNER_ID) ?: existing.ownerId,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    1
                }
                ALIAS_BY_ID -> {
                    val id = uri.lastPathSegment.orEmpty().toLongOrNull() ?: return@providerIo 0
                    val existing = dao.getAliasBlocking(id) ?: return@providerIo 0
                    dao.insertAliasBlocking(
                        existing.copy(
                            placeUuid = v.getAsString(PlacesContract.Aliases.PLACE_UUID) ?: existing.placeUuid,
                            alias = v.getAsString(PlacesContract.Aliases.ALIAS) ?: existing.alias,
                            appScope = v.getAsString(PlacesContract.Aliases.APP_SCOPE) ?: existing.appScope,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    1
                }
                    else -> 0
                }
            }
        }
        if (updated == 0) return 0
        context?.let { HubAutoExport.request(it) }
        context?.contentResolver?.notifyChange(PlacesContract.Places.CONTENT_URI, null)
        return updated
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val match = matcher.match(uri)
        val deleted = providerIo {
            DatabaseMutationCoordinator.mutex.withLock {
                val dao = PersonalHubDatabase.get(requireNotNull(context)).placeDao()
                val db = PersonalHubDatabase.get(requireNotNull(context))
                when (match) {
                PLACE_BY_UUID -> db.openHelper.writableDatabase.delete("places", "uuid = ?", arrayOf(uri.lastPathSegment.orEmpty()))
                LINK_BY_ID -> dao.deleteLinkByIdBlocking(uri.lastPathSegment.orEmpty().toLongOrNull() ?: -1L)
                ALIAS_BY_ID -> dao.deleteAliasByIdBlocking(uri.lastPathSegment.orEmpty().toLongOrNull() ?: -1L)
                else -> throw IllegalArgumentException("Unsupported delete URI: $uri")
                }
            }
        }
        if (deleted > 0) {
            context?.let { HubAutoExport.request(it) }
            context?.contentResolver?.notifyChange(PlacesContract.Places.CONTENT_URI, null)
        }
        return deleted
    }

    private fun mutationSource(match: Int, action: String): String =
        when (match) {
            PLACES, PLACE_BY_UUID -> "provider.places.$action"
            LINKS, LINK_BY_ID -> "provider.links.$action"
            ALIASES, ALIAS_BY_ID -> "provider.aliases.$action"
            EVENTS, EVENT_BY_ID -> "provider.events.$action"
            else -> "provider.$action"
        }

    private fun nearbyPlaces(dao: PlaceDao, uri: Uri): List<PlaceEntity> {
        val lat = uri.getQueryParameter(PlacesContract.Nearby.LAT)?.toDoubleOrNull()
        val lon = uri.getQueryParameter(PlacesContract.Nearby.LON)?.toDoubleOrNull()
        val radius = uri.getQueryParameter(PlacesContract.Nearby.RADIUS_M)?.toDoubleOrNull()
        if (lat == null || lon == null || radius == null) return emptyList()
        val limit = uri.getQueryParameter(PlacesContract.Nearby.LIMIT)?.toIntOrNull()
            ?.coerceIn(1, MAX_GEO_CANDIDATES)
            ?: DEFAULT_NEARBY_LIMIT
        val box = GeoSearch.boundingBox(lat, lon, radius)
        val candidates = dao.placesInBoundingBoxBlocking(
            minLat = box.minLat,
            maxLat = box.maxLat,
            minLon = box.minLon,
            maxLon = box.maxLon,
            limit = MAX_GEO_CANDIDATES,
        )
        return GeoSearch.withinRadius(candidates, lat, lon, radius)
            .take(limit)
            .map { it.place }
    }

    private fun nearestPlaces(dao: PlaceDao, uri: Uri): List<PlaceEntity> {
        val lat = uri.getQueryParameter(PlacesContract.Nearest.LAT)?.toDoubleOrNull()
        val lon = uri.getQueryParameter(PlacesContract.Nearest.LON)?.toDoubleOrNull()
        if (lat == null || lon == null) return emptyList()
        val limit = uri.getQueryParameter(PlacesContract.Nearest.LIMIT)?.toIntOrNull()
            ?.coerceIn(1, MAX_NEAREST_RESULTS)
            ?: DEFAULT_NEAREST_RESULTS
        return GeoSearch.nearest(dao.placesWithCoordinatesBlocking(MAX_NEAREST_CANDIDATES), lat, lon, limit)
            .map { it.place }
    }

    private fun ContentValues.toPlace(uuid: String, now: Long): PlaceEntity {
        return PlaceEntity(
            uuid = uuid,
            nickname = getAsString(PlacesContract.Places.NICKNAME).orEmpty(),
            address = getAsString(PlacesContract.Places.ADDRESS),
            lat = getAsDoubleOrNull(PlacesContract.Places.LAT),
            lon = getAsDoubleOrNull(PlacesContract.Places.LON),
            radiusM = getAsDoubleOrNull(PlacesContract.Places.RADIUS_M),
            notes = getAsString(PlacesContract.Places.NOTES),
            sourceApp = getAsString(PlacesContract.Places.SOURCE_APP),
            createdAt = getAsLongOrNull(PlacesContract.Places.CREATED_AT) ?: now,
            updatedAt = now,
            archived = getAsBooleanOrNull(PlacesContract.Places.ARCHIVED) ?: false,
        )
    }

    private fun ContentValues.toEvent(now: Long): PlaceEventEntity {
        val eventUuid = getAsString(PlacesContract.Events.EVENT_UUID)?.takeIf { isUuid(it) } ?: UUID.randomUUID().toString()
        return PlaceEventEntity(
            eventUuid = eventUuid,
            sessionUuid = getAsString(PlacesContract.Events.SESSION_UUID)?.takeIf { isUuid(it) } ?: eventUuid,
            placeId = getAsString(PlacesContract.Events.PLACE_ID),
            eventType = getAsString(PlacesContract.Events.EVENT_TYPE),
            timestamp = getAsLongOrNull(PlacesContract.Events.TIMESTAMP) ?: now,
            lat = getAsDoubleOrNull(PlacesContract.Events.LAT),
            lon = getAsDoubleOrNull(PlacesContract.Events.LON),
            accuracyM = getAsDoubleOrNull(PlacesContract.Events.ACCURACY_M),
            source = getAsString(PlacesContract.Events.SOURCE) ?: "PROVIDER",
            notes = getAsString(PlacesContract.Events.NOTES),
        )
    }

    private fun List<PlaceEntity>.toCursor(): Cursor {
        val cursor = MatrixCursor(PLACE_COLUMNS)
        forEach { place ->
            cursor.addRow(
                arrayOf<Any?>(
                    place.uuid,
                    place.displayName(),
                    place.uuid,
                    place.nickname,
                    place.address,
                    place.lat,
                    place.lon,
                    place.lat,
                    place.lon,
                    place.radiusM,
                    place.notes,
                    place.sourceApp,
                    place.createdAt,
                    place.updatedAt,
                    if (place.archived) 1 else 0,
                )
            )
        }
        return cursor
    }

    private fun List<PlaceEntity>.toNicknameCursor(): Cursor {
        val cursor = MatrixCursor(NICKNAME_COLUMNS)
        asSequence()
            .filter { it.nickname.isNotBlank() }
            .sortedWith(compareBy<PlaceEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.nickname })
            .forEach { place ->
                cursor.addRow(arrayOf<Any?>(place.uuid, place.nickname, place.address, place.lat, place.lon))
            }
        return cursor
    }

    private fun List<PlaceLinkEntity>.toLinkCursor(): Cursor {
        val cursor = MatrixCursor(LINK_COLUMNS)
        forEach { link ->
            cursor.addRow(
                arrayOf<Any?>(
                    link.id,
                    link.placeUuid,
                    link.ownerApp,
                    link.ownerType,
                    link.ownerId,
                    link.createdAt,
                    link.updatedAt,
                )
            )
        }
        return cursor
    }

    private fun List<PlaceAliasEntity>.toAliasCursor(): Cursor {
        val cursor = MatrixCursor(ALIAS_COLUMNS)
        forEach { alias ->
            cursor.addRow(
                arrayOf<Any?>(
                    alias.id,
                    alias.placeUuid,
                    alias.alias,
                    alias.appScope,
                    alias.createdAt,
                    alias.updatedAt,
                )
            )
        }
        return cursor
    }

    private fun List<PlaceEventEntity>.toEventCursor(): Cursor {
        val cursor = MatrixCursor(EVENT_COLUMNS)
        forEach { event ->
            cursor.addRow(
                arrayOf<Any?>(
                    event.id,
                    event.eventUuid,
                    event.sessionUuid,
                    event.placeId,
                    event.eventType,
                    event.timestamp,
                    event.lat,
                    event.lon,
                    event.accuracyM,
                    event.source,
                    event.notes,
                )
            )
        }
        return cursor
    }

    private fun ContentValues.getAsDoubleOrNull(key: String): Double? =
        runCatching { getAsDouble(key) }.getOrNull()

    private fun ContentValues.getAsLongOrNull(key: String): Long? =
        runCatching { getAsLong(key) }.getOrNull()

    private fun ContentValues.getAsBooleanOrNull(key: String): Boolean? =
        runCatching { getAsBoolean(key) }.getOrNull()

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private fun <T> providerIo(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    companion object {
        private const val PLACES = 1
        private const val PLACE_BY_UUID = 2
        private const val NEARBY = 3
        private const val LINKS = 4
        private const val LINK_BY_ID = 5
        private const val ALIASES = 6
        private const val ALIAS_BY_ID = 7
        private const val NEAREST = 8
        private const val EVENTS = 9
        private const val EVENT_BY_ID = 10
        private const val NICKNAMES = 11
        private const val DEFAULT_NEARBY_LIMIT = 100
        private const val DEFAULT_NEAREST_RESULTS = 5
        private const val MAX_NEAREST_RESULTS = 100
        private const val MAX_GEO_CANDIDATES = 20_000
        private const val MAX_NEAREST_CANDIDATES = 20_000
        private val PLACE_COLUMNS = arrayOf(
            PlacesContract.Places.PLACE_ID,
            PlacesContract.Places.DISPLAY_NAME,
            PlacesContract.Places.UUID,
            PlacesContract.Places.NICKNAME,
            PlacesContract.Places.ADDRESS,
            PlacesContract.Places.LATITUDE,
            PlacesContract.Places.LONGITUDE,
            PlacesContract.Places.LAT,
            PlacesContract.Places.LON,
            PlacesContract.Places.RADIUS_M,
            PlacesContract.Places.NOTES,
            PlacesContract.Places.SOURCE_APP,
            PlacesContract.Places.CREATED_AT,
            PlacesContract.Places.UPDATED_AT,
            PlacesContract.Places.ARCHIVED,
        )
        private val NICKNAME_COLUMNS = arrayOf(
            PlacesContract.Nicknames.STABLE_ID,
            PlacesContract.Nicknames.NICKNAME,
            PlacesContract.Nicknames.ADDRESS,
            PlacesContract.Nicknames.LATITUDE,
            PlacesContract.Nicknames.LONGITUDE,
        )
        private val LINK_COLUMNS = arrayOf(
            PlacesContract.Links.ID,
            PlacesContract.Links.PLACE_UUID,
            PlacesContract.Links.OWNER_APP,
            PlacesContract.Links.OWNER_TYPE,
            PlacesContract.Links.OWNER_ID,
            PlacesContract.Links.CREATED_AT,
            PlacesContract.Links.UPDATED_AT,
        )
        private val ALIAS_COLUMNS = arrayOf(
            PlacesContract.Aliases.ID,
            PlacesContract.Aliases.PLACE_UUID,
            PlacesContract.Aliases.ALIAS,
            PlacesContract.Aliases.APP_SCOPE,
            PlacesContract.Aliases.CREATED_AT,
            PlacesContract.Aliases.UPDATED_AT,
        )
        private val EVENT_COLUMNS = arrayOf(
            PlacesContract.Events.ID,
            PlacesContract.Events.EVENT_UUID,
            PlacesContract.Events.SESSION_UUID,
            PlacesContract.Events.PLACE_ID,
            PlacesContract.Events.EVENT_TYPE,
            PlacesContract.Events.TIMESTAMP,
            PlacesContract.Events.LAT,
            PlacesContract.Events.LON,
            PlacesContract.Events.ACCURACY_M,
            PlacesContract.Events.SOURCE,
            PlacesContract.Events.NOTES,
        )
    }
}

private fun PlaceEntity.displayName(): String =
    nickname.ifBlank { address.orEmpty() }
