package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.SubwayGraphEngine

/**
 * Moves [rideId] to start at [originStationId] and end at [destinationStationId], finding the lines
 * again and keeping its times, source and notes. With a [choice] that is one of the ways to ride
 * between the stations (express or local), the ride is stored on it and no longer asks; without
 * one, the app decides. Returns the updated ride, or null when there is no such ride, the stations
 * are the same, or no route joins them.
 */
fun SubwayDao.changeRideStations(
  rideId: String,
  originStationId: String,
  destinationStationId: String,
  graphEngine: SubwayGraphEngine,
  choice: RouteChoice? = null,
): CompleteRideRecord? {
  val existing = getRideById(rideId)
  val resolved =
    existing
      ?.takeIf { originStationId != destinationStationId }
      ?.let {
        graphEngine.resolveOriginToDestinationAuto(
          originStationId = originStationId,
          destinationStationId = destinationStationId,
          timestampEpochMs = it.ride.startTimeEpochMs,
          actualDurationMinutes = it.ride.durationMinutes,
        )
      }
      ?.takeIf { it.legs.isNotEmpty() }
  return if (existing == null || resolved == null) null
  else {
    val moved =
      SubwayDao.buildCompleteRideRecord(
        rideId = existing.ride.rideId,
        startTimeEpochMs = existing.ride.startTimeEpochMs,
        endTimeEpochMs = existing.ride.endTimeEpochMs,
        source = existing.ride.source,
        resolved = resolved,
        graphEngine = graphEngine,
        notes = existing.ride.notes,
      )
    val offered =
      resolved.ambiguousCandidates.any {
        it.routeId == choice?.routeId && it.isExpress == choice.isExpress
      }
    (if (choice != null && offered) moved.confirmedOn(choice, graphEngine) else moved).also {
      insertOrReplaceRide(it)
    }
  }
}
