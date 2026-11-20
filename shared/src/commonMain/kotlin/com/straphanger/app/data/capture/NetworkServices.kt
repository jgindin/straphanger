package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.domain.stats.SubwayStatsCalculator
import kotlin.time.Clock

/** Services that need the subway network; created once it has loaded. */
class NetworkServices(val graphEngine: SubwayGraphEngine, clock: Clock) {
  val statsCalculator = SubwayStatsCalculator(graphEngine)
  val timelineImporter = TimelineJsonImporter(graphEngine)
  val shareParser = GoogleMapsShareParser(graphEngine, clock = clock)
  val geofenceManager = DynamicGeofenceWindowManager(graphEngine)

  fun stationName(stationId: String): String = graphEngine.getStation(stationId)?.name ?: stationId
}
