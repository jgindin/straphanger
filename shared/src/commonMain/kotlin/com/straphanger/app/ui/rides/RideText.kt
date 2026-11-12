package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.StopRole
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.countOf

/** The station's name, or its ID when the network does not have it. */
internal fun SubwayGraphEngine.stationName(stationId: String): String =
  getStation(stationId)?.name ?: stationId

/** "Source: Manual • 5 stops (1 leg, 0 transfers)", flagged when the ride needs review. */
internal fun rideDetail(rec: CompleteRideRecord): String {
  val transferCount = rec.stationStops.count { it.stopRole == StopRole.TRANSFERRED }
  return "Source: ${rec.ride.source.displayLabel} • ${rec.stationStops.size} stops " +
    "(${countOf(rec.legs.size, "leg")}, ${countOf(transferCount, "transfer")})" +
    if (rec.ride.needsReview) " • NEEDS REVIEW" else ""
}
