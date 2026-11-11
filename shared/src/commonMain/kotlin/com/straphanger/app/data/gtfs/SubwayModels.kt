package com.straphanger.app.data.gtfs

import kotlinx.serialization.Serializable

@Serializable
enum class Borough(val displayName: String) {
    MANHATTAN("Manhattan"),
    BROOKLYN("Brooklyn"),
    QUEENS("Queens"),
    BRONX("The Bronx"),
    STATEN_ISLAND("Staten Island")
}

@Serializable
enum class ServicePeriod {
    DAYTIME,
    LATE_NIGHT,
    WEEKEND,
    RUSH_PEAK
}

@Serializable
enum class LateNightBehavior {
    LOCAL_ALL_NIGHT,
    EXPRESS_TO_LOCAL_LATE_NIGHT,
    SHUTTLE_LATE_NIGHT,
    NO_LATE_NIGHT_SERVICE
}

@Serializable
enum class StopRole {
    BOARDED,
    TRANSFERRED,
    EXITED,
    STOPPED_THROUGH
}

@Serializable
data class GeoPoint(
    val lat: Double,
    val lng: Double
)

@Serializable
data class SubwayRouteInfo(
    val routeId: String,
    val name: String,
    val hexColor: String,
    val textHexColor: String = "#FFFFFF",
    val trunkGroup: String,
    val division: String,
    val isExpressCapable: Boolean,
    val lateNightBehavior: LateNightBehavior,
    val totalRouteMiles: Double
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
    val expressRoutes: List<String> = emptyList(),
    val isTransferHub: Boolean = false
)

@Serializable
data class SubwaySegment(
    val segmentId: String,
    val trunkId: String,
    val fromStationId: String,
    val toStationId: String,
    val distanceMiles: Double,
    val routes: List<String>,
    val polyline: List<GeoPoint> = emptyList()
)

@Serializable
data class SubwayNetworkData(
    val totalOfficialStations: Int = 472,
    val totalOfficialComplexes: Int = 424,
    val totalOfficialSystemMiles: Double = 248.0,
    val routes: List<SubwayRouteInfo>,
    val stations: List<SubwayStation>,
    val segments: List<SubwaySegment>
)

@Serializable
data class TripLegRequest(
    val fromStationId: String,
    val toStationId: String,
    val preferredRouteId: String? = null,
    val forceExpress: Boolean? = null // null = auto based on route & timestamp
)

@Serializable
data class ResolvedStationStop(
    val stationId: String,
    val complexId: String,
    val role: StopRole,
    val legIndex: Int
)

@Serializable
data class ResolvedLegPath(
    val legIndex: Int,
    val routeId: String,
    val isExpress: Boolean,
    val servicePeriod: ServicePeriod,
    val fromStationId: String,
    val toStationId: String,
    val segmentIds: List<String>,
    val allPassedStationIds: List<String>,
    val stoppedStationIds: List<String>,
    val skippedLocalStationIds: List<String>,
    val distanceMiles: Double
)

@Serializable
data class AmbiguousRouteCandidate(
    val routeId: String,
    val isExpress: Boolean,
    val label: String,
    val estimatedMinutes: Double,
    val stoppedStationCount: Int
)

@Serializable
data class ResolvedTripResult(
    val legs: List<ResolvedLegPath>,
    val stationStops: List<ResolvedStationStop>,
    val totalDistanceMiles: Double,
    val uniqueSegmentIds: Set<String>,
    val isAmbiguous: Boolean,
    val ambiguousCandidates: List<AmbiguousRouteCandidate> = emptyList()
)
