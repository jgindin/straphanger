package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.SubwayGraphEngine

private const val MS_PER_MINUTE = 60_000L

/** The longest wait between two rides that are still one trip. */
private const val MAX_COMBINE_GAP_MS = 30 * MS_PER_MINUTE

/** How far the next ride may seem to start before this one ended, as station times are rough. */
private const val MAX_COMBINE_OVERLAP_MS = 2 * MS_PER_MINUTE

/**
 * The ride in [rides] that starts next after [ride] ends, when it could be the rest of the same
 * trip: it starts in the station complex where [ride] ended, no more than 30 minutes after [ride]
 * ended and no more than 2 minutes before. Null when there is no such ride.
 */
fun nextRideToCombine(
  rides: List<CompleteRideRecord>,
  ride: RideEntity,
  graphEngine: SubwayGraphEngine,
): CompleteRideRecord? {
  val endComplex = graphEngine.getStation(ride.destinationStationId)?.complexId
  val earliest = ride.endTimeEpochMs - MAX_COMBINE_OVERLAP_MS
  val latest = ride.endTimeEpochMs + MAX_COMBINE_GAP_MS
  return rides
    .filter {
      it.ride.rideId != ride.rideId &&
        it.ride.startTimeEpochMs > ride.startTimeEpochMs &&
        it.ride.startTimeEpochMs in earliest..latest
    }
    .minByOrNull { it.ride.startTimeEpochMs }
    ?.takeIf {
      endComplex != null && graphEngine.getStation(it.ride.originStationId)?.complexId == endComplex
    }
}

/**
 * Makes [firstId] and [secondId] one ride: from the first's origin to the second's destination,
 * from the first's start to the second's end, with the lines found again. The ride keeps the
 * first's id and source and both rides' notes; the second is deleted in the same write, and the
 * ride is then checked for overlaps. Whether it needs review (express or local, say) is the
 * resolver's call. Returns the combined ride, or null, changing nothing, when the ids are the same,
 * either ride is missing, or no route joins the stations.
 */
fun SubwayDao.combineRides(
  firstId: String,
  secondId: String,
  graphEngine: SubwayGraphEngine,
): CompleteRideRecord? {
  val first = getRideById(firstId)?.ride?.takeIf { firstId != secondId }
  val second = getRideById(secondId)?.ride
  val resolved =
    if (first == null || second == null) null
    else
      graphEngine
        .resolveOriginToDestinationAuto(
          originStationId = first.originStationId,
          destinationStationId = second.destinationStationId,
          timestampEpochMs = first.startTimeEpochMs,
          actualDurationMinutes =
            (second.endTimeEpochMs - first.startTimeEpochMs).toDouble() / MS_PER_MINUTE,
        )
        .takeIf { it.legs.isNotEmpty() }
  return if (first == null || second == null || resolved == null) null
  else
    SubwayDao.buildCompleteRideRecord(
        rideId = first.rideId,
        startTimeEpochMs = first.startTimeEpochMs,
        endTimeEpochMs = second.endTimeEpochMs,
        source = first.source,
        resolved = resolved,
        graphEngine = graphEngine,
        notes = listOf(first.notes, second.notes).filter { it.isNotBlank() }.joinToString("; "),
      )
      .also { combined ->
        insertOrReplaceRides(listOf(combined), deletingRideIds = setOf(second.rideId))
        if (lastSaveSucceeded) flagOverlappingRides(combined.ride.rideId)
      }
}
