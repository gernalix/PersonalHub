package com.gernalix.luoghi.data

import android.content.Context
import androidx.room.withTransaction
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.HubAutoExport
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Read-only import of Google Timeline records; the original JSON is never modified. */
object GoogleTimelineImport {
    private const val SOURCE = "Google Timeline"
    private const val LINK_TYPE = "google_place_id"
    private const val MAX_BYTES = 48 * 1024 * 1024
    private const val MAX_SEGMENTS = 100_000
    private val COORDINATES = Regex("""^\s*(-?\d+(?:\.\d+)?)°?,\s*(-?\d+(?:\.\d+)?)°?\s*$""")
    private val GOOGLE_ID = Regex("[A-Za-z0-9_-]{10,300}")

    data class Visit(
        val externalId: String,
        val startMs: Long,
        val endMs: Long,
        val lat: Double,
        val lon: Double,
        val name: String?,
        val address: String?,
    ) {
        val placeUuid: String get() = stableUuid("place:$externalId")
        val sessionUuid: String get() = stableUuid("session:$externalId:$startMs:$endMs")
        fun eventUuid(type: String): String = stableUuid("event:$externalId:$startMs:$endMs:$type")
    }

    data class Result(val places: Int, val visits: Int, val skipped: Int, val backupFile: String?)

    private fun stableUuid(identity: String): String = UUID.nameUUIDFromBytes(
        ("GoogleTimeline:v1:" + identity).toByteArray(Charsets.UTF_8),
    ).toString()

    /** Can be tested without touching a device, application database or credentials. */
    fun parse(content: String): Pair<List<Visit>, Int> {
        val segments = JSONObject(content).optJSONArray("semanticSegments")
            ?: error("Not a Google Timeline semanticSegments export")
        require(segments.length() in 1..MAX_SEGMENTS) { "Invalid number of Timeline segments" }
        val records = LinkedHashMap<String, Visit>()
        var invalid = 0
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONObject(i) ?: continue
            val visit = segment.optJSONObject("visit") ?: continue
            val candidate = visit.optJSONObject("topCandidate")
            val id = candidate?.optString("placeId").orEmpty().trim()
            val coordinates = candidate?.optJSONObject("placeLocation")
                ?.optString("latLng").orEmpty()
            val coords = COORDINATES.matchEntire(coordinates)
            val start = runCatching {
                OffsetDateTime.parse(segment.getString("startTime")).toInstant().toEpochMilli()
            }.getOrNull()
            val end = runCatching {
                OffsetDateTime.parse(segment.getString("endTime")).toInstant().toEpochMilli()
            }.getOrNull()
            if (!GOOGLE_ID.matches(id) || coords == null || start == null || end == null ||
                start <= 0L || end <= start
            ) {
                invalid++
                continue
            }
            val lat = coords.groupValues[1].toDoubleOrNull()
            val lon = coords.groupValues[2].toDoubleOrNull()
            if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0
            ) {
                invalid++
                continue
            }
            fun metadata(field: String, maxLength: Int): String? =
                candidate?.optString(field).orEmpty().trim().takeIf {
                    it.isNotBlank() && !it.equals("null", true) &&
                        !it.contains("before you continue", ignoreCase = true) &&
                        !it.contains("consent.google", ignoreCase = true) &&
                        it.length <= maxLength
                }
            val value = Visit(id, start, end, lat, lon,
                metadata("placeName", 160), metadata("placeAddress", 600))
            if (records.putIfAbsent(value.sessionUuid, value) != null) invalid++
        }
        require(records.isNotEmpty()) { "No valid Google Timeline visits found" }
        return records.values.toList() to invalid
    }

    private fun readBounded(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(out.size() + count <= MAX_BYTES) { "Timeline file exceeds size limit" }
            out.write(buffer, 0, count)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Conservative identity match. Never merge places solely by coordinates or address. */
    internal fun matchExistingPlace(record: Visit, places: Collection<PlaceEntity>): String? {
        val name = record.name?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() } ?: return null
        val matches = places.filter { place ->
            val latitude = place.lat
            val longitude = place.lon
            !place.archived && latitude != null && longitude != null &&
                place.nickname.trim().lowercase(Locale.ROOT) == name &&
                distanceMeters(record.lat, record.lon, latitude, longitude) <= 25.0
        }
        return matches.singleOrNull()?.uuid
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dy = phi2 - phi1
        val dx = Math.toRadians(lon2 - lon1) * kotlin.math.cos((phi1 + phi2) / 2)
        return 6_371_000.0 * kotlin.math.hypot(dx, dy)
    }

    private fun importedName(current: PlaceEntity, record: Visit): PlaceEntity? {
        if (current.sourceApp != SOURCE) return null
        val name = if (current.nickname == "Luogo Google Maps" && record.name != null) {
            record.name
        } else current.nickname
        val address = current.address ?: record.address
        return if (name != current.nickname || address != current.address) {
            current.copy(nickname = name, address = address, updatedAt = System.currentTimeMillis())
        } else null
    }

    private data class ExistingVisit(val placeUuid: String, val startMs: Long, val endMs: Long)

    private fun existingVisits(events: List<PlaceEventEntity>): MutableSet<ExistingVisit> =
        events.groupBy { it.sessionUuid }.values.mapNotNull { eventsInSession ->
            val start = eventsInSession.firstOrNull { it.eventType == PlaceEventTypes.CHECK_IN }
            val end = eventsInSession.firstOrNull { it.eventType == PlaceEventTypes.CHECK_OUT }
            if (start != null && end != null && start.placeId == end.placeId &&
                end.timestamp > start.timestamp
            ) ExistingVisit(start.placeId, start.timestamp, end.timestamp) else null
        }.toMutableSet()

    /** One validated rollback backup and one transaction; repeated imports are idempotent. */
    suspend fun importFrom(context: Context, stream: InputStream): Result =
        withContext(Dispatchers.IO) {
            val (visits, rejected) = parse(readBounded(stream))
            val app = context.applicationContext
            val database = PersonalHubDatabase.get(app)
            val dao = database.placeDao()
            val summary = DatabaseMutationCoordinator.mutex.withLock {
                val oldEvents = dao.listEvents()
                val oldPlaces = dao.listAllPlacesForBackup()
                val oldLinks = dao.listLinksForBackup()
                val oldEventIds = oldEvents.mapTo(hashSetOf()) { it.eventUuid }
                val linkIndex = oldLinks.filter {
                    it.ownerApp == SOURCE && it.ownerType == LINK_TYPE
                }.groupBy { it.ownerId }
                require(linkIndex.values.all { group ->
                    group.map { it.placeUuid }.distinct().size == 1
                }) { "Ambiguous Google Timeline place link in existing database" }
                val linked = linkIndex.mapValues { it.value.first().placeUuid }
                val current = oldPlaces.associateBy { it.uuid }
                val pendingEvents = visits.any {
                    it.eventUuid(PlaceEventTypes.CHECK_IN) !in oldEventIds ||
                        it.eventUuid(PlaceEventTypes.CHECK_OUT) !in oldEventIds
                }
                val missingLinks = visits.any { it.externalId !in linked }
                val staleMetadata = visits.any { visit ->
                    val existing = (linked[visit.externalId] ?: visit.placeUuid)
                        .let(current::get)
                    existing != null && importedName(existing, visit) != null
                }
                if (!pendingEvents && !missingLinks && !staleMetadata) {
                    return@withLock Result(0, 0, rejected + visits.size, null)
                }
                val original = DatabaseVault.backupCurrent(app)
                val saved = File(app.noBackupFilesDir,
                    "timeline-pre-import-" + System.currentTimeMillis() + ".db")
                try {
                    FileInputStream(original).use { input ->
                        FileOutputStream(saved).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    require(saved.length() == original.length() && saved.length() > 0L) {
                        "Timeline pre-import backup incomplete"
                    }
                    fun digest(file: File): ByteArray {
                        val d = java.security.MessageDigest.getInstance("SHA-256")
                        FileInputStream(file).use { fileStream ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = fileStream.read(buffer)
                                if (count < 0) break
                                d.update(buffer, 0, count)
                            }
                        }
                        return d.digest()
                    }
                    require(digest(saved).contentEquals(digest(original))) {
                        "Timeline pre-import backup integrity mismatch"
                    }
                } catch (failure: Exception) {
                    saved.delete()
                    throw failure
                }

                var places = 0
                var imported = 0
                var skipped = rejected
                database.withTransaction {
                    val placesById = oldPlaces.associateBy { it.uuid }.toMutableMap()
                    val externalLinks = linked.toMutableMap()
                    val eventUuids = oldEventIds.toMutableSet()
                    val knownVisits = existingVisits(oldEvents)
                    for (record in visits) {
                        val placeUuid = externalLinks[record.externalId] ?: run {
                            val chosen = if (record.placeUuid in placesById) {
                                record.placeUuid
                            } else {
                                matchExistingPlace(record, placesById.values) ?: record.placeUuid
                            }
                            if (chosen !in placesById) {
                                val created = PlaceEntity(
                                    uuid = chosen,
                                    nickname = record.name ?: "Luogo Google Maps",
                                    address = record.address,
                                    lat = record.lat,
                                    lon = record.lon,
                                    sourceApp = SOURCE,
                                )
                                dao.upsertPlace(created)
                                placesById[chosen] = created
                                places++
                            }
                            dao.insertLink(PlaceLinkEntity(
                                placeUuid = chosen,
                                ownerApp = SOURCE,
                                ownerType = LINK_TYPE,
                                ownerId = record.externalId,
                            ))
                            externalLinks[record.externalId] = chosen
                            chosen
                        }
                        val place = requireNotNull(placesById[placeUuid]) {
                            "Timeline place link refers to missing place"
                        }
                        importedName(place, record)?.let {
                            dao.upsertPlace(it)
                            placesById[placeUuid] = it
                        }

                        val enter = record.eventUuid(PlaceEventTypes.CHECK_IN)
                        val leave = record.eventUuid(PlaceEventTypes.CHECK_OUT)
                        val visit = ExistingVisit(placeUuid, record.startMs, record.endMs)
                        if (enter in eventUuids || leave in eventUuids || visit in knownVisits) {
                            skipped++
                            continue
                        }
                        dao.insertEvent(PlaceEventEntity(
                            eventUuid = enter,
                            sessionUuid = record.sessionUuid,
                            placeId = placeUuid,
                            eventType = PlaceEventTypes.CHECK_IN,
                            timestamp = record.startMs,
                            lat = record.lat,
                            lon = record.lon,
                            source = SOURCE,
                        ))
                        dao.insertEvent(PlaceEventEntity(
                            eventUuid = leave,
                            sessionUuid = record.sessionUuid,
                            placeId = placeUuid,
                            eventType = PlaceEventTypes.CHECK_OUT,
                            timestamp = record.endMs,
                            lat = record.lat,
                            lon = record.lon,
                            source = SOURCE,
                        ))
                        eventUuids.add(enter)
                        eventUuids.add(leave)
                        knownVisits.add(visit)
                        imported++
                    }
                    if (imported > 0) dao.recalculateStatsBaselines()
                }
                Result(places, imported, skipped, saved.name)
            }
            if (summary.backupFile != null) HubAutoExport.request(app)
            summary
        }
}
