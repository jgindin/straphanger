package com.straphanger.app.ui.rides

import com.straphanger.app.data.local.CompleteRideRecord

/**
 * A trip the rider has taken before, offered as a chip on the log sheet.
 *
 * @param transferStationId where the rider changed trains; null for a ride on one train.
 * @param routeId the line, when every ride on this trip was on that one line; null otherwise.
 * @param label "168 St → 14 St", or "Bedford Av → 86 St via 14 St-Union Sq" with a change.
 */
internal data class TripSuggestion(
  val originStationId: String,
  val transferStationId: String?,
  val destinationStationId: String,
  val routeId: String?,
  val label: String,
)

/** A first leg, a change and a second leg: the most the log form can log. */
private const val MAX_LEGS = 2

private data class TripKey(val origin: String, val transfer: String?, val destination: String)

/** Where [this] ride changed trains first, or null when it stayed on one train. */
private fun CompleteRideRecord.firstTransferStationId(): String? =
  legs.sortedBy { it.legIndex }.takeIf { it.size > 1 }?.first()?.toStationId

/**
 * The rider's own trips, most often taken first and, among trips taken as often, the most recent
 * first; each trip (origin, change, destination) appears once. Left out are rides with a station
 * [stationName] does not know, rides that start and end at the same station, rides that still need
 * review, and rides with more than one change, which the log form (one change at most) could not
 * log again as the same trip.
 *
 * @param stationName the station's name, or null when the network does not have it.
 */
internal fun suggestedTrips(
  rides: List<CompleteRideRecord>,
  stationName: (String) -> String?,
  limit: Int = 6,
): List<TripSuggestion> {
  val byTrip =
    rides
      .filter { rec ->
        rec.ride.originStationId != rec.ride.destinationStationId &&
          !rec.ride.needsReview &&
          rec.legs.size <= MAX_LEGS
      }
      .groupBy {
        TripKey(it.ride.originStationId, it.firstTransferStationId(), it.ride.destinationStationId)
      }
      .filterKeys { key ->
        stationName(key.origin) != null &&
          stationName(key.destination) != null &&
          (key.transfer == null || stationName(key.transfer) != null)
      }
  return byTrip.entries
    .sortedWith(
      compareByDescending<Map.Entry<TripKey, List<CompleteRideRecord>>> { it.value.size }
        .thenByDescending { entry -> entry.value.maxOf { it.ride.startTimeEpochMs } },
    )
    .take(limit)
    .map { (key, trips) ->
      val origin = stationName(key.origin)
      val destination = stationName(key.destination)
      val via = key.transfer?.let { " via ${stationName(it)}" }.orEmpty()
      TripSuggestion(
        originStationId = key.origin,
        transferStationId = key.transfer,
        destinationStationId = key.destination,
        routeId = trips.singleLineRouteId(),
        label = "$origin → $destination$via",
      )
    }
}

/** The line every one of [this] rides was on, when each was one leg on the same line. */
private fun List<CompleteRideRecord>.singleLineRouteId(): String? {
  val routes = map { rec -> rec.legs.singleOrNull()?.routeId }
  return routes.first()?.takeIf { first -> routes.all { it == first } }
}
