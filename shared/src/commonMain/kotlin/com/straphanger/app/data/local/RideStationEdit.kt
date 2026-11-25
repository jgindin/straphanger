package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.SubwayGraphEngine

/**
 * Moves [rideId] to start at [originStationId] and end at [destinationStationId], finding the lines
 * again and keeping its times, source and notes. [choices] holds a train for some legs, by leg
 * index; each one that is one of that leg's ways to ride (express or local) is stored and the leg
 * no longer asks. A choice that is not offered for its leg is ignored, and a leg without one is
 * left to the app. A ride that did not need review does not start to; one that did keeps asking
 * while a leg left to the app has an express and a local. Returns the updated ride, or null when
 * there is no such ride, the stations are the same, or no route joins them.
 */
fun SubwayDao.changeRideStations(
  rideId: String,
  originStationId: String,
  destinationStationId: String,
  graphEngine: SubwayGraphEngine,
  choices: Map<Int, RouteChoice> = emptyMap(),
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
        forceNeedsReview = existing.keptReviewPolicy(),
      )
    val offered = choices.filter { (legIndex, choice) ->
      resolved.ambiguousCandidates.any {
        it.legIndex == legIndex && it.routeId == choice.routeId && it.isExpress == choice.isExpress
      }
    }
    (if (offered.isNotEmpty()) moved.confirmedOn(offered, graphEngine) else moved).also {
      insertOrReplaceRide(it)
    }
  }
}
