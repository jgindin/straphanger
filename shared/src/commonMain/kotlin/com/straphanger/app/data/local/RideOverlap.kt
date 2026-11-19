package com.straphanger.app.data.local

/**
 * Flags [rideId] and every other ride whose time span overlaps it for review, and records the
 * overlaps on each. Does nothing for a ride that overlaps none. Returns true when rides were
 * flagged.
 */
fun SubwayDao.flagOverlappingRides(rideId: String): Boolean {
  val all = getAllRides()
  val ride = all.firstOrNull { it.ride.rideId == rideId }
  val overlapping = all.filter {
    ride != null && it.ride.rideId != rideId && ride.ride.overlaps(it.ride)
  }
  if (ride != null && overlapping.isNotEmpty()) {
    val flagged =
      listOf(ride to overlapping.map { it.ride.rideId }) +
        overlapping.map { other ->
          other to (listOf(rideId) + other.ride.overlapsRideIds).distinct()
        }
    insertOrReplaceRides(
      flagged.map { (rec, ids) ->
        rec.copy(ride = rec.ride.copy(needsReview = true, overlapsRideIds = ids))
      },
    )
  }
  return overlapping.isNotEmpty()
}

private fun RideEntity.overlaps(other: RideEntity): Boolean =
  startTimeEpochMs < other.endTimeEpochMs && other.startTimeEpochMs < endTimeEpochMs
