package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.GeoPoint
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.TripLegRequest
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

@Serializable
data class TimelineImportSummary(
    val rawSegmentsFound: Int,
    val ridesImported: Int,
    val multiLegRidesMerged: Int,
    val duplicatesSkipped: Int,
    val unmatchedSegmentsSkipped: Int,
    val importedRecords: List<CompleteRideRecord>
)

/**
 * Tri-Format Google Maps Timeline JSON Importer supporting:
 * 1. Android on-device Timeline.json ("semanticSegments" with "40.7347°, -73.9899°" degree strings)
 * 2. iOS on-device Timeline.json (root array or "semanticSegments" with "geo:40.7347,-73.9899" URIs)
 * 3. Google Takeout Semantic Location History ("timelineObjects" -> "activitySegment" with E7 integers & transitPath)
 */
class TimelineJsonImporter(
    private val graphEngine: SubwayGraphEngine
) {
    private val json = Json { ignoreUnknownKeys = true }

    private data class RawSubwaySegment(
        val startTimeEpochMs: Long,
        val endTimeEpochMs: Long,
        val startPoint: GeoPoint,
        val endPoint: GeoPoint,
        val explicitRouteId: String? = null
    )

    fun importTimelineJson(
        rawJson: String,
        dao: SubwayDao? = null
    ): TimelineImportSummary {
        val rootElement = runCatching { json.parseToJsonElement(rawJson.trim()) }.getOrNull()
            ?: return TimelineImportSummary(0, 0, 0, 0, 0, emptyList())

        val extractedSegments = mutableListOf<RawSubwaySegment>()

        when (rootElement) {
            is JsonArray -> {
                // iOS on-device Timeline root array
                for (item in rootElement) {
                    if (item is JsonObject) {
                        parseSemanticSegmentObject(item)?.let { extractedSegments.add(it) }
                    }
                }
            }
            is JsonObject -> {
                // Format 1 & 2: "semanticSegments" array
                rootElement["semanticSegments"]?.let { segArr ->
                    if (segArr is JsonArray) {
                        for (item in segArr) {
                            if (item is JsonObject) {
                                parseSemanticSegmentObject(item)?.let { extractedSegments.add(it) }
                            }
                        }
                    }
                }
                // Format 3: Google Takeout "timelineObjects" array
                rootElement["timelineObjects"]?.let { tlArr ->
                    if (tlArr is JsonArray) {
                        for (item in tlArr) {
                            val actSeg = (item as? JsonObject)?.get("activitySegment") as? JsonObject
                            if (actSeg != null) {
                                parseTakeoutActivitySegment(actSeg)?.let { extractedSegments.add(it) }
                            }
                        }
                    }
                }
            }
            else -> {}
        }

        val sortedRaw = extractedSegments.sortedBy { it.startTimeEpochMs }

        // Snap each segment to origin/destination stations
        data class SnappedLegCandidate(
            val startTimeEpochMs: Long,
            val endTimeEpochMs: Long,
            val fromStationId: String,
            val toStationId: String,
            val explicitRouteId: String?
        )

        var unmatchedCount = 0
        val snappedLegs = mutableListOf<SnappedLegCandidate>()
        for (seg in sortedRaw) {
            val fromSt = graphEngine.findNearestStation(
                lat = seg.startPoint.lat,
                lng = seg.startPoint.lng,
                maxDistanceMiles = 0.55,
                preferredRouteId = seg.explicitRouteId
            )
            val toSt = graphEngine.findNearestStation(
                lat = seg.endPoint.lat,
                lng = seg.endPoint.lng,
                maxDistanceMiles = 0.55,
                preferredRouteId = seg.explicitRouteId
            )
            if (fromSt == null || toSt == null || fromSt.id == toSt.id) {
                unmatchedCount++
            } else {
                snappedLegs.add(
                    SnappedLegCandidate(
                        startTimeEpochMs = seg.startTimeEpochMs,
                        endTimeEpochMs = seg.endTimeEpochMs,
                        fromStationId = fromSt.id,
                        toStationId = toSt.id,
                        explicitRouteId = seg.explicitRouteId
                    )
                )
            }
        }

        // Group consecutive legs within a 15-minute in-system transfer window into a single multi-leg ride
        val groupedTrips = mutableListOf<List<SnappedLegCandidate>>()
        var multiLegMergedCount = 0
        for (leg in snappedLegs) {
            val currentGroup = groupedTrips.lastOrNull()
            if (currentGroup != null) {
                val prevLeg = currentGroup.last()
                val gapMs = leg.startTimeEpochMs - prevLeg.endTimeEpochMs
                val prevEndComplex = graphEngine.getStation(prevLeg.toStationId)?.complexId
                val nextStartComplex = graphEngine.getStation(leg.fromStationId)?.complexId
                val isTransferConnection = gapMs in -60_000L..(15 * 60_000L) &&
                    prevEndComplex != null && prevEndComplex == nextStartComplex
                if (isTransferConnection) {
                    groupedTrips[groupedTrips.lastIndex] = currentGroup + leg
                    multiLegMergedCount++
                    continue
                }
            }
            groupedTrips.add(listOf(leg))
        }

        val existingRides = dao?.getAllRides().orEmpty()
        var duplicateCount = 0
        val importedRecords = mutableListOf<CompleteRideRecord>()

        for ((idx, legGroup) in groupedTrips.withIndex()) {
            val tripStartMs = legGroup.first().startTimeEpochMs
            val tripEndMs = legGroup.last().endTimeEpochMs
            val originId = legGroup.first().fromStationId
            val destId = legGroup.last().toStationId
            val originComplex = graphEngine.getStation(originId)?.complexId
            val destComplex = graphEngine.getStation(destId)?.complexId

            val isDuplicate = existingRides.any { existing ->
                val exOriginComplex = graphEngine.getStation(existing.ride.originStationId)?.complexId
                val exDestComplex = graphEngine.getStation(existing.ride.destinationStationId)?.complexId
                abs(existing.ride.startTimeEpochMs - tripStartMs) <= 5 * 60_000L &&
                    exOriginComplex == originComplex &&
                    exDestComplex == destComplex
            } || importedRecords.any { already ->
                val alOriginComplex = graphEngine.getStation(already.ride.originStationId)?.complexId
                val alDestComplex = graphEngine.getStation(already.ride.destinationStationId)?.complexId
                abs(already.ride.startTimeEpochMs - tripStartMs) <= 5 * 60_000L &&
                    alOriginComplex == originComplex &&
                    alDestComplex == destComplex
            }

            if (isDuplicate) {
                duplicateCount++
                continue
            }

            val durationMinutes = ((tripEndMs - tripStartMs) / 60000.0).coerceAtLeast(1.0)
            val resolved = if (legGroup.size == 1 && legGroup.single().explicitRouteId == null) {
                graphEngine.resolveOriginToDestinationAuto(
                    originStationId = originId,
                    destinationStationId = destId,
                    preferredRouteId = null,
                    timestampEpochMs = tripStartMs,
                    actualDurationMinutes = durationMinutes
                )
            } else {
                graphEngine.resolveTrip(
                    legs = legGroup.map { l ->
                        TripLegRequest(
                            fromStationId = l.fromStationId,
                            toStationId = l.toStationId,
                            preferredRouteId = l.explicitRouteId
                        )
                    },
                    timestampEpochMs = tripStartMs,
                    actualDurationMinutes = durationMinutes
                )
            }

            if (resolved.legs.isEmpty()) {
                unmatchedCount++
                continue
            }

            val record = SubwayDao.buildCompleteRideRecord(
                rideId = "tl_${tripStartMs}_$idx",
                startTimeEpochMs = tripStartMs,
                endTimeEpochMs = tripEndMs,
                source = RideSource.TIMELINE_IMPORT,
                resolved = resolved,
                graphEngine = graphEngine
            )
            importedRecords.add(record)
        }

        dao?.insertOrReplaceRides(importedRecords)

        return TimelineImportSummary(
            rawSegmentsFound = sortedRaw.size,
            ridesImported = importedRecords.size,
            multiLegRidesMerged = multiLegMergedCount,
            duplicatesSkipped = duplicateCount,
            unmatchedSegmentsSkipped = unmatchedCount,
            importedRecords = importedRecords
        )
    }

    private fun parseSemanticSegmentObject(obj: JsonObject): RawSubwaySegment? {
        val activity = obj["activity"] as? JsonObject ?: return null
        val topCandidate = activity["topCandidate"] as? JsonObject
        val type = topCandidate?.get("type")?.jsonPrimitive?.contentOrNull
            ?: activity["type"]?.jsonPrimitive?.contentOrNull
            ?: return null

        if (!isSubwayActivityType(type)) return null

        val startTimeStr = obj["startTime"]?.jsonPrimitive?.contentOrNull ?: return null
        val endTimeStr = obj["endTime"]?.jsonPrimitive?.contentOrNull ?: startTimeStr
        val startMs = parseIsoTimestampMs(startTimeStr) ?: return null
        val endMs = parseIsoTimestampMs(endTimeStr) ?: (startMs + 15 * 60_000L)

        val startCoord = parseFlexibleGeoPoint(activity["start"])
            ?: parsePointFromTimelinePath(obj["timelinePath"], isFirst = true)
            ?: return null
        val endCoord = parseFlexibleGeoPoint(activity["end"])
            ?: parsePointFromTimelinePath(obj["timelinePath"], isFirst = false)
            ?: return null

        val explicitRoute = extractRouteBullet(
            (activity["transitPath"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                ?: topCandidate?.get("transitLine")?.jsonPrimitive?.contentOrNull
        )

        return RawSubwaySegment(
            startTimeEpochMs = startMs,
            endTimeEpochMs = endMs,
            startPoint = startCoord,
            endPoint = endCoord,
            explicitRouteId = explicitRoute
        )
    }

    private fun parseTakeoutActivitySegment(actSeg: JsonObject): RawSubwaySegment? {
        val actType = actSeg["activityType"]?.jsonPrimitive?.contentOrNull
            ?: (actSeg["topCandidate"] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            ?: return null
        if (!isSubwayActivityType(actType)) return null

        val durationObj = actSeg["duration"] as? JsonObject
        val startMs = durationObj?.get("startTimestamp")?.jsonPrimitive?.contentOrNull?.let { parseIsoTimestampMs(it) }
            ?: durationObj?.get("startTimestampMs")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: return null
        val endMs = durationObj?.get("endTimestamp")?.jsonPrimitive?.contentOrNull?.let { parseIsoTimestampMs(it) }
            ?: durationObj?.get("endTimestampMs")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: (startMs + 15 * 60_000L)

        val startCoord = parseFlexibleGeoPoint(actSeg["startLocation"]) ?: return null
        val endCoord = parseFlexibleGeoPoint(actSeg["endLocation"]) ?: return null

        val transitPath = actSeg["transitPath"] as? JsonObject
        val explicitRoute = extractRouteBullet(
            transitPath?.get("name")?.jsonPrimitive?.contentOrNull
                ?: transitPath?.get("line")?.jsonPrimitive?.contentOrNull
        )

        return RawSubwaySegment(
            startTimeEpochMs = startMs,
            endTimeEpochMs = endMs,
            startPoint = startCoord,
            endPoint = endCoord,
            explicitRouteId = explicitRoute
        )
    }

    private fun isSubwayActivityType(type: String): Boolean {
        val upper = type.uppercase()
        return upper == "IN_SUBWAY" || upper == "SUBWAY" || upper == "IN_TRAIN"
    }

    /**
     * Normalizes all 3 coordinate formats used by Google Maps Timeline:
     * - Format 1 (Android degree strings): {"latLng": "40.7347°, -73.9899°"} or "40.7347° N, 73.9899° W"
     * - Format 2 (iOS geo URIs): "geo:40.7347,-73.9899" or {"latLng": "geo:40.7347,-73.9899"}
     * - Format 3 (Takeout E7 integers): {"latitudeE7": 407347000, "longitudeE7": -739899000}
     */
    fun parseFlexibleGeoPoint(element: JsonElement?): GeoPoint? {
        if (element == null) return null
        if (element is JsonObject) {
            element["latLng"]?.let { return parseFlexibleGeoPoint(it) }
            element["point"]?.let { return parseFlexibleGeoPoint(it) }
            val latE7 = element["latitudeE7"]?.jsonPrimitive?.intOrNull
            val lngE7 = element["longitudeE7"]?.jsonPrimitive?.intOrNull
            if (latE7 != null && lngE7 != null) {
                return GeoPoint(latE7 / 1e7, lngE7 / 1e7)
            }
            val lat = element["lat"]?.jsonPrimitive?.doubleOrNull ?: element["latitude"]?.jsonPrimitive?.doubleOrNull
            val lng = element["lng"]?.jsonPrimitive?.doubleOrNull ?: element["longitude"]?.jsonPrimitive?.doubleOrNull
            if (lat != null && lng != null) {
                return GeoPoint(lat, lng)
            }
            return null
        }

        val rawStr = runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()?.trim() ?: return null
        val cleaned = rawStr.removePrefix("geo:").trim()
        val parts = cleaned.split(",")
        if (parts.size < 2) return null

        val lat = parseCoordinateToken(parts[0]) ?: return null
        val lng = parseCoordinateToken(parts[1]) ?: return null
        return GeoPoint(lat, lng)
    }

    private fun parseCoordinateToken(token: String): Double? {
        val upper = token.trim().uppercase()
        val isNegativeCardinal = upper.endsWith("S") || upper.endsWith("W")
        val numericOnly = upper
            .replace("°", "")
            .replace("N", "")
            .replace("S", "")
            .replace("E", "")
            .replace("W", "")
            .trim()
        val value = numericOnly.toDoubleOrNull() ?: return null
        return if (isNegativeCardinal && value > 0) -value else value
    }

    private fun parsePointFromTimelinePath(pathElem: JsonElement?, isFirst: Boolean): GeoPoint? {
        val arr = pathElem as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        val target = if (isFirst) arr.first() else arr.last()
        return parseFlexibleGeoPoint(target)
    }

    private fun parseIsoTimestampMs(raw: String): Long? {
        return runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
    }

    private fun extractRouteBullet(rawLineName: String?): String? {
        if (rawLineName.isNullOrBlank()) return null
        val cleaned = rawLineName.trim().uppercase()
            .removeSuffix(" TRAIN")
            .removeSuffix(" LINE")
            .removeSuffix(" EXPRESS")
            .removeSuffix(" LOCAL")
            .trim()
        return cleaned.takeIf { graphEngine.getRoute(it) != null }
    }
}
