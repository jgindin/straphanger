package com.straphanger.app.data.gtfs

/**
 * Looks up stations, routes and segments of [network] and resolves rides on it. Path search is done
 * by [RoutePathFinder] and route choice by [TripResolver].
 */
class SubwayGraphEngine(val network: SubwayNetworkData) {
  private val stationById: Map<String, SubwayStation> = network.stations.associateBy { it.id }
  private val stationsByComplex: Map<String, List<SubwayStation>> =
    network.stations.groupBy { it.complexId }
  private val routeById: Map<String, SubwayRouteInfo> = network.routes.associateBy { it.routeId }
  private val segmentById: Map<String, SubwaySegment> =
    network.segments.associateBy { it.segmentId }
  private val routeOrder: Map<String, Int> =
    network.routes.withIndex().associate { (i, r) -> r.routeId to i }

  private val pathFinder = RoutePathFinder(network.segments, stationsByComplex)
  private val tripResolver = TripResolver(pathFinder, routeById, routeOrder, stationById)

  fun getStation(id: String): SubwayStation? = stationById[id]

  fun getRoute(routeId: String): SubwayRouteInfo? = routeById[routeId]

  fun getSegment(segmentId: String): SubwaySegment? = segmentById[segmentId]

  fun getStationsInComplex(complexId: String): List<SubwayStation> =
    stationsByComplex[complexId].orEmpty()

  fun searchStations(query: String): List<SubwayStation> {
    val q = normalizeStationName(query)
    if (q.isEmpty()) return network.stations
    return network.stations.filter {
      normalizeStationName(it.name).contains(q) ||
        normalizeStationName(it.complexName).contains(q) ||
        it.borough.displayName.lowercase().contains(q) ||
        it.daytimeRoutes.any { r -> r.lowercase() == q }
    }
  }

  /**
   * Lowercases and maps "Avenue"/"Ave" to "av" and "Street" to "st", as MTA station names spell
   * them.
   */
  private fun normalizeStationName(name: String): String =
    name
      .trim()
      .lowercase()
      .replace(Regex("""\b(avenue|ave)\b"""), "av")
      .replace(Regex("""\bstreet\b"""), "st")

  fun findNearestStation(
    lat: Double,
    lng: Double,
    maxDistanceMiles: Double = 0.45,
    preferredRouteId: String? = null,
  ): SubwayStation? {
    val candidates =
      network.stations
        .map { st ->
          val dist = NycSubwayNetworkCatalog.haversineMiles(lat, lng, st.lat, st.lng)
          st to dist
        }
        .filter { it.second <= maxDistanceMiles }
        .sortedBy { (st, dist) ->
          val routeBonus =
            if (preferredRouteId != null && preferredRouteId in st.daytimeRoutes) -0.04 else 0.0
          dist + routeBonus
        }
    return candidates.firstOrNull()?.first
  }

  fun inferServicePeriod(timestampEpochMs: Long): ServicePeriod = servicePeriodAt(timestampEpochMs)

  /**
   * Resolves an Origin -> Destination request, automatically inserting in-system transfers where no
   * single route serves the whole shortest path.
   */
  fun resolveOriginToDestinationAuto(
    originStationId: String,
    destinationStationId: String,
    preferredRouteId: String? = null,
    forceExpress: Boolean? = null,
    timestampEpochMs: Long,
    actualDurationMinutes: Double? = null,
  ): ResolvedTripResult {
    val rawPath =
      pathFinder.findShortestPath(originStationId, destinationStationId, preferredRouteId)
    return if (rawPath.none { it.segment != null }) {
      ResolvedTripResult(
        legs = emptyList(),
        stationStops = emptyList(),
        totalDistanceMiles = 0.0,
        uniqueSegmentIds = emptySet(),
        isAmbiguous = false,
      )
    } else {
      resolveTrip(
        legs = legRequestsAlong(rawPath, preferredRouteId, forceExpress),
        timestampEpochMs = timestampEpochMs,
        actualDurationMinutes = actualDurationMinutes,
      )
    }
  }

  /** Resolves an explicit 1..N leg trip (supporting multi-leg transfers that count as 1 ride). */
  fun resolveTrip(
    legs: List<TripLegRequest>,
    timestampEpochMs: Long,
    actualDurationMinutes: Double? = null,
  ): ResolvedTripResult = tripResolver.resolveTrip(legs, timestampEpochMs, actualDurationMinutes)
}
