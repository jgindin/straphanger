package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.GeoPoint
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.TripLegRequest
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class TimelineImportSummary(
  val rawSegmentsFound: Int,
  val ridesImported: Int,
  val multiLegRidesMerged: Int,
  val duplicatesSkipped: Int,
  val unmatchedSegmentsSkipped: Int,
  val importedRecords: List<CompleteRideRecord>,
)

/**
 * Google Maps Timeline JSON importer for three formats:
 * 1. Android on-device Timeline.json ("semanticSegments" with "40.7347°, -73.9899°" degree strings)
 * 2. iOS on-device Timeline.json (root array or "semanticSegments" with "geo:40.7347,-73.9899"
 *    URIs)
 * 3. Google Takeout Semantic Location History ("timelineObjects" -> "activitySegment" with E7
 *    integers & transitPath)
 */
class TimelineJsonImporter(private val graphEngine: SubwayGraphEngine) {
  private val json = Json { ignoreUnknownKeys = true }
  private val segmentParser = TimelineSegmentParser { graphEngine.getRoute(it) != null }

  /** A segment whose ends snapped to two different stations. */
  private data class SnappedLeg(
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val fromStationId: String,
    val toStationId: String,
    val explicitRouteId: String?,
  )

  /** What became of the grouped trips: the new rides and how many were skipped and why. */
  private data class TripResolution(
    val records: List<CompleteRideRecord>,
    val duplicates: Int,
    val unresolved: Int,
  )

  fun importTimelineJson(
    rawJson: String,
    dao: SubwayDao? = null,
  ): TimelineImportSummary {
    val summary = resolveTimelineJson(rawJson, dao?.getAllRides().orEmpty())
    dao?.insertOrReplaceRides(summary.importedRecords)
    return summary
  }

  /**
   * Parses and resolves a Timeline file without touching storage. Rides already in [existingRides]
   * are skipped as duplicates; the caller saves [TimelineImportSummary.importedRecords]. Text that
   * is not JSON is logged and yields an empty summary.
   */
  fun resolveTimelineJson(
    rawJson: String,
    existingRides: List<CompleteRideRecord>,
  ): TimelineImportSummary {
    val root = parseRoot(rawJson) ?: return TimelineImportSummary(0, 0, 0, 0, 0, emptyList())
    val segments = segmentParser.segments(root).sortedBy { it.startTimeEpochMs }
    val snapped = segments.map { snap(it) }
    val legs = snapped.filterNotNull()
    val trips = groupTransfers(legs)
    val resolution = resolveTrips(trips, existingRides)
    return TimelineImportSummary(
      rawSegmentsFound = segments.size,
      ridesImported = resolution.records.size,
      multiLegRidesMerged = legs.size - trips.size,
      duplicatesSkipped = resolution.duplicates,
      unmatchedSegmentsSkipped = snapped.count { it == null } + resolution.unresolved,
      importedRecords = resolution.records,
    )
  }

  private fun parseRoot(rawJson: String): JsonElement? =
    try {
      json.parseToJsonElement(rawJson.trim())
    } catch (e: SerializationException) {
      DiagnosticsLog.warn(LOG_TAG, "Timeline file is not valid JSON", e)
      null
    }

  /** Snaps the segment's ends to the nearest stations; null when they are missing or the same. */
  private fun snap(seg: RawSubwaySegment): SnappedLeg? {
    val fromSt = nearestStation(seg.startPoint, seg.explicitRouteId)
    val toSt = nearestStation(seg.endPoint, seg.explicitRouteId)
    return if (fromSt != null && toSt != null && fromSt.id != toSt.id) {
      SnappedLeg(
        startTimeEpochMs = seg.startTimeEpochMs,
        endTimeEpochMs = seg.endTimeEpochMs,
        fromStationId = fromSt.id,
        toStationId = toSt.id,
        explicitRouteId = seg.explicitRouteId,
      )
    } else {
      null
    }
  }

  private fun nearestStation(point: GeoPoint, routeId: String?) =
    graphEngine.findNearestStation(
      lat = point.lat,
      lng = point.lng,
      maxDistanceMiles = MAX_SNAP_MILES,
      preferredRouteId = routeId,
    )

  /**
   * Groups consecutive legs joined by an in-system transfer (see [isTransfer]) into one multi-leg
   * trip.
   */
  private fun groupTransfers(legs: List<SnappedLeg>): List<List<SnappedLeg>> {
    val groups = mutableListOf<MutableList<SnappedLeg>>()
    for (leg in legs) {
      val current = groups.lastOrNull()
      if (current != null && isTransfer(current.last(), leg)) current += leg
      else groups += mutableListOf(leg)
    }
    return groups
  }

  /**
   * True when [next] starts in the complex where [previous] ended, from a minute before to
   * [TRANSFER_WINDOW_MS] after it ended.
   */
  private fun isTransfer(previous: SnappedLeg, next: SnappedLeg): Boolean {
    val gapMs = next.startTimeEpochMs - previous.endTimeEpochMs
    val prevEndComplex = graphEngine.complexOf(previous.toStationId)
    return gapMs in -TRANSFER_OVERLAP_MS..TRANSFER_WINDOW_MS &&
      prevEndComplex != null &&
      prevEndComplex == graphEngine.complexOf(next.fromStationId)
  }

  private fun resolveTrips(
    trips: List<List<SnappedLeg>>,
    existingRides: List<CompleteRideRecord>,
  ): TripResolution {
    val records = mutableListOf<CompleteRideRecord>()
    var duplicates = 0
    var unresolved = 0
    trips.forEachIndexed { idx, legs ->
      val duplicate = isDuplicate(legs, existingRides) || isDuplicate(legs, records)
      val record = if (duplicate) null else rideFor(legs, idx)
      when {
        duplicate -> duplicates++
        record == null -> unresolved++
        else -> records += record
      }
    }
    return TripResolution(records, duplicates, unresolved)
  }

  /**
   * True when [rides] hold a ride between the same complexes starting within
   * [DUPLICATE_START_WINDOW_MS] of the trip.
   */
  private fun isDuplicate(legs: List<SnappedLeg>, rides: List<CompleteRideRecord>): Boolean {
    val startMs = legs.first().startTimeEpochMs
    val originComplex = graphEngine.complexOf(legs.first().fromStationId)
    val destComplex = graphEngine.complexOf(legs.last().toStationId)
    return rides.any {
      abs(it.ride.startTimeEpochMs - startMs) <= DUPLICATE_START_WINDOW_MS &&
        graphEngine.complexOf(it.ride.originStationId) == originComplex &&
        graphEngine.complexOf(it.ride.destinationStationId) == destComplex
    }
  }

  /** The ride for one trip, or null when no route joins its stations. */
  private fun rideFor(legs: List<SnappedLeg>, idx: Int): CompleteRideRecord? {
    val tripStartMs = legs.first().startTimeEpochMs
    val tripEndMs = legs.last().endTimeEpochMs
    val durationMinutes = ((tripEndMs - tripStartMs) / 60000.0).coerceAtLeast(1.0)
    val resolved =
      if (legs.size == 1 && legs.single().explicitRouteId == null) {
        graphEngine.resolveOriginToDestinationAuto(
          originStationId = legs.single().fromStationId,
          destinationStationId = legs.single().toStationId,
          preferredRouteId = null,
          timestampEpochMs = tripStartMs,
          actualDurationMinutes = durationMinutes,
        )
      } else {
        graphEngine.resolveTrip(
          legs =
            legs.map { l ->
              TripLegRequest(
                fromStationId = l.fromStationId,
                toStationId = l.toStationId,
                preferredRouteId = l.explicitRouteId,
              )
            },
          timestampEpochMs = tripStartMs,
          actualDurationMinutes = durationMinutes,
        )
      }
    return resolved.legs
      .takeIf { it.isNotEmpty() }
      ?.let {
        SubwayDao.buildCompleteRideRecord(
          rideId = "tl_${tripStartMs}_$idx",
          startTimeEpochMs = tripStartMs,
          endTimeEpochMs = tripEndMs,
          source = RideSource.TIMELINE_IMPORT,
          resolved = resolved,
          graphEngine = graphEngine,
        )
      }
  }
}

private fun SubwayGraphEngine.complexOf(stationId: String): String? =
  getStation(stationId)?.complexId

private const val LOG_TAG = "StraphangerTimeline"

/** How far a segment's end may be from the station it is snapped to. */
private const val MAX_SNAP_MILES = 0.55

/** A transfer may start up to this long before the previous leg's recorded end. */
private const val TRANSFER_OVERLAP_MS = 60_000L

/** The longest in-system transfer between two legs of one trip. */
private const val TRANSFER_WINDOW_MS = 15 * 60_000L

/** Rides between the same complexes starting this close together are the same ride. */
private const val DUPLICATE_START_WINDOW_MS = 5 * 60_000L
