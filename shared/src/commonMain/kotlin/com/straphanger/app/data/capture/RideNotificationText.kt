package com.straphanger.app.data.capture

import com.straphanger.app.data.local.CompleteRideRecord
import kotlin.math.roundToInt

/** What the ride-recorded notification says. */
data class RideNotificationText(val title: String, val text: String)

fun rideNotificationText(
  record: CompleteRideRecord,
  stationName: (String) -> String,
): RideNotificationText {
  val ride = record.ride
  val lines =
    record.legs
      .sortedBy { it.legIndex }
      .map { leg -> if (leg.routeId.length > 1) leg.routeId.removeSuffix("X") else leg.routeId }
      .fold(emptyList<String>()) { acc, line -> if (acc.lastOrNull() == line) acc else acc + line }
      .joinToString(" → ")
  val minutes = maxOf(1, ride.durationMinutes.roundToInt())
  val suffix =
    when {
      ride.needsReview && ride.ambiguousCandidates.isNotEmpty() ->
        " · Express or local? Tap to confirm"
      ride.needsReview -> " · Tap to check this ride"
      else -> ""
    }
  return RideNotificationText(
    title =
      "$lines · ${stationName(ride.originStationId)} → ${stationName(ride.destinationStationId)}",
    text = "$minutes min$suffix",
  )
}
