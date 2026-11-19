package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayStation

/** A station offered in the station editor, and how far it is from the station being replaced. */
internal data class StationChoice(val station: SubwayStation, val milesAway: Double?)

private const val MAX_STATION_CHOICES = 40

/**
 * The stations matching [query] (all of them for a blank query), nearest to [nearStationId] first.
 * Every platform is listed on its own, so "14 St" offers the A/C/E, the 1/2/3, the F/M and the L
 * stations, each with its lines. Without a known [nearStationId] the order is by name.
 */
internal fun stationChoices(
  graphEngine: SubwayGraphEngine,
  query: String,
  nearStationId: String?,
): List<StationChoice> {
  val near = nearStationId?.let(graphEngine::getStation)
  return graphEngine
    .searchStations(query)
    .map { station ->
      StationChoice(
        station,
        near?.let {
          NycSubwayNetworkCatalog.haversineMiles(it.lat, it.lng, station.lat, station.lng)
        },
      )
    }
    .sortedWith(compareBy<StationChoice> { it.milesAway }.thenBy { it.station.name })
    .take(MAX_STATION_CHOICES)
}

private const val TENTHS = 10

/** "0.4 mi", or "here" for the station itself. */
internal fun distanceText(miles: Double): String {
  val tenths = kotlin.math.round(miles * TENTHS).toInt()
  return if (tenths == 0) "here" else "${tenths / TENTHS}.${tenths % TENTHS} mi"
}

/** [routes] without express variants ("FX", "6X") whose line is listed too; they draw the same. */
internal fun bulletRoutes(routes: List<String>): List<String> = routes.filterNot {
  it.length > 1 && it.endsWith("X") && it.dropLast(1) in routes
}
