package com.straphanger.app.data.gtfs

import kotlin.math.abs
import kotlin.math.roundToInt

/** Rough average speeds and per-stop dwell times used to estimate trip durations. */
private const val EXPRESS_MPH = 24.0
private const val LOCAL_MPH = 15.5
private const val EXPRESS_DWELL_MINUTES = 0.45
private const val LOCAL_DWELL_MINUTES = 0.55

private fun roundToHundredths(value: Double): Double = (value * 100.0).roundToInt() / 100.0

private fun roundToTenths(value: Double): Double = (value * 10.0).roundToInt() / 10.0

/** The stations of a leg the train stops at, and the ones it runs through. */
private data class StopPlan(val stopped: List<String>, val skipped: List<String>)

/** The track one leg rides and, for each route that serves all of it, where that route stops. */
private class LegCorridor(
  val orderedStations: List<String>,
  val segments: List<SubwaySegment>,
  val candidateRoutes: List<String>,
  val plans: Map<String, StopPlan>,
) {
  val distanceMiles = roundToHundredths(segments.sumOf { it.distanceMiles })
  val expressRoutes = candidateRoutes.filter { plans.getValue(it).skipped.isNotEmpty() }
  val localRoutes = candidateRoutes.filter { plans.getValue(it).skipped.isEmpty() }
  val allStops = StopPlan(orderedStations, emptyList())

  fun estimatedMinutes(stopCount: Int, express: Boolean): Double =
    if (express) (distanceMiles / EXPRESS_MPH) * 60.0 + (stopCount - 1) * EXPRESS_DWELL_MINUTES
    else (distanceMiles / LOCAL_MPH) * 60.0 + (stopCount - 1) * LOCAL_DWELL_MINUTES
}

/** The route chosen for a leg, where it stops, and the alternatives to offer the user. */
private data class RouteChoice(
  val routeId: String,
  val plan: StopPlan,
  val candidates: List<AmbiguousRouteCandidate> = emptyList(),
)

private data class LegResolution(
  val leg: ResolvedLegPath,
  val stops: List<ResolvedStationStop>,
  val candidates: List<AmbiguousRouteCandidate>,
)

/** What every leg of one trip shares. */
private class TripContext(
  val servicePeriod: ServicePeriod,
  val lastLegIndex: Int,
  val actualDurationMinutes: Double?,
)

/**
 * Turns leg requests into resolved legs: the track each leg rides, the route that served it, and
 * the stations it stopped at.
 */
internal class TripResolver(
  private val pathFinder: RoutePathFinder,
  private val routeById: Map<String, SubwayRouteInfo>,
  private val routeOrder: Map<String, Int>,
  private val stationById: Map<String, SubwayStation>,
) {
  fun resolveTrip(
    legs: List<TripLegRequest>,
    timestampEpochMs: Long,
    actualDurationMinutes: Double?,
  ): ResolvedTripResult {
    val trip = TripContext(servicePeriodAt(timestampEpochMs), legs.lastIndex, actualDurationMinutes)
    val resolutions =
      legs.withIndex().mapNotNull { (legIdx, legReq) -> resolveLeg(legIdx, legReq, trip) }
    val resolvedLegs = resolutions.map { it.leg }
    val candidates = resolutions.flatMap { it.candidates }
    return ResolvedTripResult(
      legs = resolvedLegs,
      stationStops = resolutions.flatMap { it.stops },
      totalDistanceMiles = roundToHundredths(resolvedLegs.sumOf { it.distanceMiles }),
      uniqueSegmentIds = resolvedLegs.flatMap { it.segmentIds }.toSet(),
      isAmbiguous = candidates.isNotEmpty(),
      ambiguousCandidates = candidates,
    )
  }

  private fun resolveLeg(legIdx: Int, legReq: TripLegRequest, trip: TripContext): LegResolution? {
    val trackSteps =
      pathFinder
        .findShortestPath(legReq.fromStationId, legReq.toStationId, legReq.preferredRouteId)
        .filter { it.segment != null }
    if (trackSteps.isEmpty()) return null
    val corridor = corridorFor(trackSteps, trip.servicePeriod)
    // Express and local alternatives are offered only for a single-leg trip with no chosen route.
    val offerCandidates = trip.lastLegIndex == 0 && legReq.preferredRouteId == null
    val choice = chooseRoute(corridor, legReq, trip.actualDurationMinutes, offerCandidates)
    val leg =
      ResolvedLegPath(
        legIndex = legIdx,
        routeId = choice.routeId,
        isExpress = choice.plan.skipped.isNotEmpty(),
        servicePeriod = trip.servicePeriod,
        fromStationId = corridor.orderedStations.first(),
        toStationId = corridor.orderedStations.last(),
        segmentIds = corridor.segments.map { it.segmentId },
        allPassedStationIds = corridor.orderedStations,
        stoppedStationIds = choice.plan.stopped,
        skippedLocalStationIds = choice.plan.skipped,
        distanceMiles = corridor.distanceMiles,
      )
    return LegResolution(leg, stationStops(legIdx, trip.lastLegIndex, leg), choice.candidates)
  }

  private fun corridorFor(trackSteps: List<PathStep>, servicePeriod: ServicePeriod): LegCorridor {
    val segments = trackSteps.mapNotNull { it.segment }
    val orderedStations =
      listOf(trackSteps.first().fromStationId) + trackSteps.map { it.toStationId }
    val candidateRoutes =
      segments
        .map { it.routes.toSet() }
        .reduceOrNull { acc, set -> acc.intersect(set) }
        ?.toList()
        .orEmpty()
        .ifEmpty { segments.first().routes }
        .sortedBy { routeOrder[it] ?: Int.MAX_VALUE }
    val plans = candidateRoutes.associateWith { r -> stopPlan(r, servicePeriod, orderedStations) }
    return LegCorridor(orderedStations, segments, candidateRoutes, plans)
  }

  private fun chooseRoute(
    corridor: LegCorridor,
    legReq: TripLegRequest,
    actualDurationMinutes: Double?,
    offerCandidates: Boolean,
  ): RouteChoice {
    val preferred = legReq.preferredRouteId
    val forceExpress = legReq.forceExpress
    return when {
      preferred != null && preferred in corridor.candidateRoutes ->
        RouteChoice(preferred, preferredRoutePlan(corridor, preferred, forceExpress))
      forceExpress != null -> forcedServiceChoice(corridor, forceExpress)
      corridor.expressRoutes.isNotEmpty() && corridor.localRoutes.isNotEmpty() ->
        choiceByDuration(corridor, actualDurationMinutes, offerCandidates)
      else -> {
        val routeId =
          corridor.expressRoutes.firstOrNull()
            ?: corridor.localRoutes.firstOrNull()
            ?: corridor.candidateRoutes.first()
        RouteChoice(routeId, corridor.plans[routeId] ?: corridor.allStops)
      }
    }
  }

  private fun preferredRoutePlan(
    corridor: LegCorridor,
    routeId: String,
    forceExpress: Boolean?,
  ): StopPlan =
    when (forceExpress) {
      false -> corridor.allStops
      true ->
        corridor.plans.getValue(routeId).takeIf { it.skipped.isNotEmpty() }
          ?: stopPlan(routeId, ServicePeriod.DAYTIME, corridor.orderedStations)
      null -> corridor.plans.getValue(routeId)
    }

  private fun forcedServiceChoice(corridor: LegCorridor, forceExpress: Boolean): RouteChoice =
    if (forceExpress && corridor.expressRoutes.isNotEmpty()) {
      val routeId = corridor.expressRoutes.first()
      RouteChoice(routeId, corridor.plans.getValue(routeId))
    } else {
      RouteChoice(
        corridor.localRoutes.firstOrNull() ?: corridor.candidateRoutes.first(),
        corridor.allStops,
      )
    }

  // Both an express and a local serve this stretch: use the ride's duration if known.
  private fun choiceByDuration(
    corridor: LegCorridor,
    actualDurationMinutes: Double?,
    offerCandidates: Boolean,
  ): RouteChoice {
    val expRoute = corridor.expressRoutes.first()
    val locRoute = corridor.localRoutes.first()
    val expPlan = corridor.plans.getValue(expRoute)
    val estExpressMinutes = corridor.estimatedMinutes(expPlan.stopped.size, express = true)
    val estLocalMinutes = corridor.estimatedMinutes(corridor.orderedStations.size, express = false)
    val candidates =
      if (offerCandidates) {
        listOf(
          AmbiguousRouteCandidate(
            routeId = expRoute,
            isExpress = true,
            label = "$expRoute Express",
            estimatedMinutes = roundToTenths(estExpressMinutes),
            stoppedStationCount = expPlan.stopped.size,
          ),
          AmbiguousRouteCandidate(
            routeId = locRoute,
            isExpress = false,
            label = "$locRoute Local",
            estimatedMinutes = roundToTenths(estLocalMinutes),
            stoppedStationCount = corridor.orderedStations.size,
          ),
        )
      } else {
        emptyList()
      }
    val preferLocalByDuration =
      actualDurationMinutes != null &&
        abs(actualDurationMinutes - estLocalMinutes) <
          abs(actualDurationMinutes - estExpressMinutes)
    return if (preferLocalByDuration) {
      RouteChoice(locRoute, corridor.allStops, candidates)
    } else {
      RouteChoice(expRoute, expPlan, candidates)
    }
  }

  private fun stationStops(
    legIdx: Int,
    lastLegIndex: Int,
    leg: ResolvedLegPath,
  ): List<ResolvedStationStop> {
    val stopped = leg.stoppedStationIds
    return stopped.withIndex().mapNotNull { (stopIdx, stId) ->
      stationById[stId]?.let { st ->
        val role =
          when {
            legIdx == 0 && stopIdx == 0 -> StopRole.BOARDED
            legIdx == lastLegIndex && stopIdx == stopped.lastIndex -> StopRole.EXITED
            stopIdx == 0 || stopIdx == stopped.lastIndex -> StopRole.TRANSFERRED
            else -> StopRole.STOPPED_THROUGH
          }
        ResolvedStationStop(
          stationId = stId,
          complexId = st.complexId,
          role = role,
          legIndex = legIdx,
        )
      }
    }
  }

  /**
   * Which of [orderedStations] the route stops at in [period], from its GTFS stop pattern (falling
   * back to the DAYTIME pattern). Endpoints are always stops. An intermediate station is skipped
   * only when it is missing from the pattern while stations on both sides of it on this leg are in
   * the pattern; stations off the pattern's stretch (another branch, say) count as stops.
   */
  private fun stopPlan(
    routeId: String,
    period: ServicePeriod,
    orderedStations: List<String>,
  ): StopPlan {
    val pattern = routeById[routeId]?.stopsFor(period)?.toSet().orEmpty()
    val inPattern = orderedStations.map { it in pattern }
    val firstIn = inPattern.indexOfFirst { it }
    val lastIn = inPattern.indexOfLast { it }
    val (skipped, stopped) =
      orderedStations.withIndex().partition { (idx, _) ->
        val isEndpoint = idx == 0 || idx == orderedStations.lastIndex
        !isEndpoint && !inPattern[idx] && firstIn in 0 until idx && lastIn > idx
      }
    return StopPlan(stopped.map { it.value }, skipped.map { it.value })
  }
}

/**
 * Splits a path into leg requests wherever it changes trains: a route change at a station, or a
 * walk between platforms of a station complex. [preferredRouteId] is kept only on the legs it
 * serves throughout.
 */
internal fun legRequestsAlong(
  path: List<PathStep>,
  preferredRouteId: String?,
  forceExpress: Boolean?,
): List<TripLegRequest> {
  val legRequests = mutableListOf<TripLegRequest>()
  var legStart: String? = null
  var legEnd: String? = null
  var legRoute: String? = null
  var legRoutes: Set<String> = emptySet()

  fun closeLeg() {
    val from = legStart
    val to = legEnd
    if (from != null && to != null) {
      legRequests.add(
        TripLegRequest(
          fromStationId = from,
          toStationId = to,
          preferredRouteId = preferredRouteId?.takeIf { it in legRoutes },
          forceExpress = forceExpress,
        ),
      )
      legStart = null
      legEnd = null
      legRoute = null
      legRoutes = emptySet()
    }
  }

  for (step in path) {
    val seg = step.segment
    if (seg == null || (legStart != null && step.routeId != legRoute)) closeLeg()
    if (seg != null) {
      if (legStart == null) {
        legStart = step.fromStationId
        legRoute = step.routeId
        legRoutes = seg.routes.toSet()
      } else {
        legRoutes = legRoutes intersect seg.routes.toSet()
      }
      legEnd = step.toStationId
    }
  }
  closeLeg()
  return legRequests
}
