package com.straphanger.app.data.commute

import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.gtfs.SubwayStation

/** A station complex as the rider sees it: one name, possibly several platforms' stations. */
data class StationComplex(
  val complexId: String,
  val name: String,
  val stationIds: List<String>,
  val routes: List<String>,
)

/** The network's station complexes, sorted by name. */
fun stationComplexes(network: SubwayNetworkData): List<StationComplex> =
  network.stations
    .groupBy(SubwayStation::complexId)
    .map { (id, stations) ->
      StationComplex(
        complexId = id,
        name = stations.first().complexName,
        stationIds = stations.map { it.id },
        routes = stations.flatMap { it.daytimeRoutes + it.lateNightRoutes }.distinct().sorted(),
      )
    }
    .sortedBy { it.name }
