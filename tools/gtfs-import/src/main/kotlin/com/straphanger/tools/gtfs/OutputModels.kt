package com.straphanger.tools.gtfs

import kotlinx.serialization.Serializable

// Mirrors of the @Serializable network classes in
// shared/src/commonMain/kotlin/com/straphanger/app/data/gtfs/SubwayModels.kt.
// :shared has no JVM target, so the tool keeps its own copy. BundledNetworkTest in :shared
// decodes the generated file with the app's classes, which catches any drift between the two.

@Serializable
enum class Borough {
  MANHATTAN,
  BROOKLYN,
  QUEENS,
  BRONX,
  STATEN_ISLAND,
}

@Serializable
enum class ServicePeriod {
  DAYTIME,
  LATE_NIGHT,
  WEEKEND,
  RUSH_PEAK,
}

@Serializable data class GeoPoint(val lat: Double, val lng: Double)

@Serializable
data class SubwayRouteInfo(
  val routeId: String,
  val name: String,
  val hexColor: String,
  val textHexColor: String,
  val trunkGroup: String,
  val division: String,
  val isExpressCapable: Boolean,
  val totalRouteMiles: Double,
  val stopsAt: Map<ServicePeriod, List<String>>,
)

@Serializable
data class SubwayStation(
  val id: String,
  val name: String,
  val complexId: String,
  val complexName: String,
  val borough: Borough,
  val lat: Double,
  val lng: Double,
  val daytimeRoutes: List<String>,
  val lateNightRoutes: List<String>,
  val isTransferHub: Boolean,
)

@Serializable
data class SubwaySegment(
  val segmentId: String,
  val trunkId: String,
  val fromStationId: String,
  val toStationId: String,
  val distanceMiles: Double,
  val routes: List<String>,
  val polyline: List<GeoPoint>,
)

@Serializable
data class SubwayNetworkData(
  val sourceDate: String,
  val routes: List<SubwayRouteInfo>,
  val stations: List<SubwayStation>,
  val segments: List<SubwaySegment>,
) {
  val stationCount: Int
    get() = stations.size

  val complexCount: Int
    get() = stations.distinctBy { it.complexId }.size

  val systemMiles: Double
    get() = segments.sumOf { it.distanceMiles }
}

/** One entry of route-metadata.json. */
@Serializable
data class RouteMetadata(
  val trunkGroup: String,
  val division: String,
  val name: String,
)
