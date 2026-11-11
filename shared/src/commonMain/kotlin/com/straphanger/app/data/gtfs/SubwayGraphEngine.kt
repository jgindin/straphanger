package com.straphanger.app.data.gtfs

import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

class SubwayGraphEngine(
    val network: SubwayNetworkData
) {
    private val stationById: Map<String, SubwayStation> = network.stations.associateBy { it.id }
    private val stationsByComplex: Map<String, List<SubwayStation>> = network.stations.groupBy { it.complexId }
    private val routeById: Map<String, SubwayRouteInfo> = network.routes.associateBy { it.routeId }
    private val segmentById: Map<String, SubwaySegment> = network.segments.associateBy { it.segmentId }
    private val routeOrder: Map<String, Int> = network.routes.withIndex().associate { (i, r) -> r.routeId to i }

    private data class AdjacencyEdge(
        val toStationId: String,
        val segment: SubwaySegment?, // null if in-complex transfer walk
        val costMiles: Double
    )

    private val adjacency: Map<String, List<AdjacencyEdge>> by lazy {
        val map = mutableMapOf<String, MutableList<AdjacencyEdge>>()
        for (seg in network.segments) {
            map.getOrPut(seg.fromStationId) { mutableListOf() }
                .add(AdjacencyEdge(seg.toStationId, seg, seg.distanceMiles))
            map.getOrPut(seg.toStationId) { mutableListOf() }
                .add(AdjacencyEdge(seg.fromStationId, seg, seg.distanceMiles))
        }
        // Connect sibling platforms inside the same station complex with a small transfer penalty
        for ((_, complexStations) in stationsByComplex) {
            if (complexStations.size > 1) {
                for (i in complexStations.indices) {
                    for (j in i + 1 until complexStations.size) {
                        val a = complexStations[i]
                        val b = complexStations[j]
                        map.getOrPut(a.id) { mutableListOf() }
                            .add(AdjacencyEdge(b.id, null, 0.05))
                        map.getOrPut(b.id) { mutableListOf() }
                            .add(AdjacencyEdge(a.id, null, 0.05))
                    }
                }
            }
        }
        map
    }

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

    /** Lowercases and maps "Avenue"/"Ave" to "av" and "Street" to "st", as MTA station names spell them. */
    private fun normalizeStationName(name: String): String =
        name.trim().lowercase()
            .replace(Regex("""\b(avenue|ave)\b"""), "av")
            .replace(Regex("""\bstreet\b"""), "st")

    fun findNearestStation(
        lat: Double,
        lng: Double,
        maxDistanceMiles: Double = 0.45,
        preferredRouteId: String? = null
    ): SubwayStation? {
        val candidates = network.stations.map { st ->
            val dist = NycSubwayNetworkCatalog.haversineMiles(lat, lng, st.lat, st.lng)
            st to dist
        }.filter { it.second <= maxDistanceMiles }
            .sortedBy { (st, dist) ->
                val routeBonus = if (preferredRouteId != null && preferredRouteId in st.daytimeRoutes) -0.04 else 0.0
                dist + routeBonus
            }
        return candidates.firstOrNull()?.first
    }

    fun inferServicePeriod(timestampEpochMs: Long): ServicePeriod {
        val dt = Instant.fromEpochMilliseconds(timestampEpochMs)
            .toLocalDateTime(TimeZone.of("America/New_York"))
        val hour = dt.hour
        val minute = dt.minute
        // Late night: 11:45 PM - 5:29 AM
        if (hour in 0..4 || (hour == 23 && minute >= 45) || (hour == 5 && minute < 30)) {
            return ServicePeriod.LATE_NIGHT
        }
        if (dt.dayOfWeek == DayOfWeek.SATURDAY || dt.dayOfWeek == DayOfWeek.SUNDAY) {
            return ServicePeriod.WEEKEND
        }
        if (hour in 7..9 || hour in 16..19) {
            return ServicePeriod.RUSH_PEAK
        }
        return ServicePeriod.DAYTIME
    }

    /**
     * Resolves an Origin -> Destination request, automatically inserting in-system transfers
     * where no single route serves the whole shortest path.
     */
    fun resolveOriginToDestinationAuto(
        originStationId: String,
        destinationStationId: String,
        preferredRouteId: String? = null,
        forceExpress: Boolean? = null,
        timestampEpochMs: Long,
        actualDurationMinutes: Double? = null
    ): ResolvedTripResult {
        val rawPath = findShortestPath(originStationId, destinationStationId, preferredRouteId)
        val segmentsOnly = rawPath.mapNotNull { it.segment }
        if (segmentsOnly.isEmpty()) {
            return ResolvedTripResult(
                legs = emptyList(),
                stationStops = emptyList(),
                totalDistanceMiles = 0.0,
                uniqueSegmentIds = emptySet(),
                isAmbiguous = false
            )
        }

        // Split into legs wherever no single route serves the next segment, or the path walks
        // between platforms of a station complex.
        val legRequests = mutableListOf<TripLegRequest>()
        var legStart: String? = null
        var legEnd: String? = null
        var legRoutes: Set<String> = emptySet()

        fun closeLeg() {
            val from = legStart ?: return
            val to = legEnd ?: return
            legRequests.add(
                TripLegRequest(
                    fromStationId = from,
                    toStationId = to,
                    preferredRouteId = preferredRouteId?.takeIf { it in legRoutes },
                    forceExpress = forceExpress
                )
            )
            legStart = null
            legEnd = null
            legRoutes = emptySet()
        }

        for (step in rawPath) {
            val seg = step.segment
            if (seg == null) {
                closeLeg()
                continue
            }
            val shared = legRoutes intersect seg.routes.toSet()
            if (legStart != null && shared.isEmpty()) {
                closeLeg()
            }
            if (legStart == null) {
                legStart = step.fromStationId
                legRoutes = seg.routes.toSet()
            } else {
                legRoutes = shared
            }
            legEnd = step.toStationId
        }
        closeLeg()

        return resolveTrip(
            legs = legRequests,
            timestampEpochMs = timestampEpochMs,
            actualDurationMinutes = actualDurationMinutes
        )
    }

    /**
     * Resolves an explicit 1..N leg trip (supporting multi-leg transfers that count as 1 ride).
     */
    fun resolveTrip(
        legs: List<TripLegRequest>,
        timestampEpochMs: Long,
        actualDurationMinutes: Double? = null
    ): ResolvedTripResult {
        val servicePeriod = inferServicePeriod(timestampEpochMs)
        val resolvedLegs = mutableListOf<ResolvedLegPath>()
        val stationStops = mutableListOf<ResolvedStationStop>()
        val allCandidates = mutableListOf<AmbiguousRouteCandidate>()
        var anyAmbiguous = false

        for ((legIdx, legReq) in legs.withIndex()) {
            val steps = findShortestPath(legReq.fromStationId, legReq.toStationId, legReq.preferredRouteId)
            val trackSteps = steps.filter { it.segment != null }
            if (trackSteps.isEmpty()) continue

            val segments = trackSteps.mapNotNull { it.segment }
            val orderedStations = mutableListOf<String>()
            orderedStations.add(trackSteps.first().fromStationId)
            for (ts in trackSteps) {
                orderedStations.add(ts.toStationId)
            }

            val candidateRoutesOnCorridor = segments
                .map { it.routes.toSet() }
                .reduceOrNull { acc, set -> acc.intersect(set) }
                ?.toList()
                .orEmpty()
                .ifEmpty { segments.first().routes }
                .sortedBy { routeOrder[it] ?: Int.MAX_VALUE }

            val legDistance = (segments.sumOf { it.distanceMiles } * 100.0).roundToInt() / 100.0

            val plans = candidateRoutesOnCorridor.associateWith { r -> stopPlan(r, servicePeriod, orderedStations) }
            val expressRoutes = candidateRoutesOnCorridor.filter { plans.getValue(it).skipped.isNotEmpty() }
            val localRoutes = candidateRoutesOnCorridor.filter { plans.getValue(it).skipped.isEmpty() }
            val allStops = StopPlan(orderedStations, emptyList())

            fun estimatedMinutes(stopCount: Int, express: Boolean): Double =
                if (express) (legDistance / 24.0) * 60.0 + (stopCount - 1) * 0.45
                else (legDistance / 15.5) * 60.0 + (stopCount - 1) * 0.55

            val chosenRouteId: String
            val chosenPlan: StopPlan

            if (legReq.preferredRouteId != null && legReq.preferredRouteId in candidateRoutesOnCorridor) {
                chosenRouteId = legReq.preferredRouteId
                chosenPlan = when (legReq.forceExpress) {
                    false -> allStops
                    true -> plans.getValue(chosenRouteId).takeIf { it.skipped.isNotEmpty() }
                        ?: stopPlan(chosenRouteId, ServicePeriod.DAYTIME, orderedStations)
                    null -> plans.getValue(chosenRouteId)
                }
            } else if (legReq.forceExpress != null) {
                if (legReq.forceExpress && expressRoutes.isNotEmpty()) {
                    chosenRouteId = expressRoutes.first()
                    chosenPlan = plans.getValue(chosenRouteId)
                } else {
                    chosenRouteId = localRoutes.firstOrNull() ?: candidateRoutesOnCorridor.first()
                    chosenPlan = allStops
                }
            } else if (expressRoutes.isNotEmpty() && localRoutes.isNotEmpty()) {
                // Both an express and a local serve this stretch: use the ride's duration if known.
                val expRoute = expressRoutes.first()
                val locRoute = localRoutes.first()
                val expPlan = plans.getValue(expRoute)
                val estExpressMinutes = estimatedMinutes(expPlan.stopped.size, express = true)
                val estLocalMinutes = estimatedMinutes(orderedStations.size, express = false)
                if (legs.size == 1 && legReq.preferredRouteId == null) {
                    anyAmbiguous = true
                    allCandidates.add(
                        AmbiguousRouteCandidate(
                            routeId = expRoute,
                            isExpress = true,
                            label = "$expRoute Express",
                            estimatedMinutes = (estExpressMinutes * 10.0).roundToInt() / 10.0,
                            stoppedStationCount = expPlan.stopped.size
                        )
                    )
                    allCandidates.add(
                        AmbiguousRouteCandidate(
                            routeId = locRoute,
                            isExpress = false,
                            label = "$locRoute Local",
                            estimatedMinutes = (estLocalMinutes * 10.0).roundToInt() / 10.0,
                            stoppedStationCount = orderedStations.size
                        )
                    )
                }
                val preferLocalByDuration = actualDurationMinutes != null &&
                    abs(actualDurationMinutes - estLocalMinutes) < abs(actualDurationMinutes - estExpressMinutes)
                if (preferLocalByDuration) {
                    chosenRouteId = locRoute
                    chosenPlan = allStops
                } else {
                    chosenRouteId = expRoute
                    chosenPlan = expPlan
                }
            } else {
                chosenRouteId = expressRoutes.firstOrNull() ?: localRoutes.firstOrNull() ?: candidateRoutesOnCorridor.first()
                chosenPlan = plans[chosenRouteId] ?: allStops
            }

            val chosenIsExpress = chosenPlan.skipped.isNotEmpty()
            val stoppedStations = chosenPlan.stopped
            val skippedStations = chosenPlan.skipped

            resolvedLegs.add(
                ResolvedLegPath(
                    legIndex = legIdx,
                    routeId = chosenRouteId,
                    isExpress = chosenIsExpress,
                    servicePeriod = servicePeriod,
                    fromStationId = orderedStations.first(),
                    toStationId = orderedStations.last(),
                    segmentIds = segments.map { it.segmentId },
                    allPassedStationIds = orderedStations,
                    stoppedStationIds = stoppedStations,
                    skippedLocalStationIds = skippedStations,
                    distanceMiles = legDistance
                )
            )

            for ((stopIdx, stId) in stoppedStations.withIndex()) {
                val st = stationById[stId] ?: continue
                val role = when {
                    legIdx == 0 && stopIdx == 0 -> StopRole.BOARDED
                    legIdx == legs.lastIndex && stopIdx == stoppedStations.lastIndex -> StopRole.EXITED
                    stopIdx == 0 || stopIdx == stoppedStations.lastIndex -> StopRole.TRANSFERRED
                    else -> StopRole.STOPPED_THROUGH
                }
                stationStops.add(
                    ResolvedStationStop(
                        stationId = stId,
                        complexId = st.complexId,
                        role = role,
                        legIndex = legIdx
                    )
                )
            }
        }

        val totalMiles = (resolvedLegs.sumOf { it.distanceMiles } * 100.0).roundToInt() / 100.0
        val uniqueSegments = resolvedLegs.flatMap { it.segmentIds }.toSet()

        return ResolvedTripResult(
            legs = resolvedLegs,
            stationStops = stationStops,
            totalDistanceMiles = totalMiles,
            uniqueSegmentIds = uniqueSegments,
            isAmbiguous = anyAmbiguous,
            ambiguousCandidates = allCandidates
        )
    }

    private data class StopPlan(val stopped: List<String>, val skipped: List<String>)

    /**
     * Which of [orderedStations] the route stops at in [period], from its GTFS stop pattern
     * (falling back to the DAYTIME pattern). Endpoints are always stops. An intermediate station
     * is skipped only when it is missing from the pattern while stations on both sides of it on
     * this leg are in the pattern; stations off the pattern's stretch (another branch, say) count
     * as stops.
     */
    private fun stopPlan(routeId: String, period: ServicePeriod, orderedStations: List<String>): StopPlan {
        val pattern = routeById[routeId]?.stopsFor(period)?.toSet().orEmpty()
        val inPattern = orderedStations.map { it in pattern }
        val firstIn = inPattern.indexOfFirst { it }
        val lastIn = inPattern.indexOfLast { it }
        val stopped = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((idx, stId) in orderedStations.withIndex()) {
            val isEndpoint = idx == 0 || idx == orderedStations.lastIndex
            val skips = !isEndpoint && !inPattern[idx] && firstIn in 0 until idx && lastIn > idx
            if (skips) skipped.add(stId) else stopped.add(stId)
        }
        return StopPlan(stopped, skipped)
    }

    private data class PathStep(
        val fromStationId: String,
        val toStationId: String,
        val segment: SubwaySegment?
    )

    private fun findShortestPath(
        startStationId: String,
        endStationId: String,
        preferredRouteId: String?
    ): List<PathStep> {
        if (startStationId == endStationId) return emptyList()
        val dist = mutableMapOf<String, Double>().withDefault { Double.POSITIVE_INFINITY }
        val prev = mutableMapOf<String, PathStep>()
        val visited = mutableSetOf<String>()

        dist[startStationId] = 0.0
        val queue = mutableListOf(startStationId)

        while (queue.isNotEmpty()) {
            queue.sortBy { dist.getValue(it) }
            val current = queue.removeAt(0)
            if (current == endStationId) break
            if (!visited.add(current)) continue

            val currentCost = dist.getValue(current)

            for (edge in adjacency[current].orEmpty()) {
                if (edge.toStationId in visited) continue
                val seg = edge.segment
                // In-complex platform transfer penalty so single-seat rides win
                val transferPenalty = if (seg == null) 0.35 else 0.0
                val routePenalty = if (preferredRouteId != null && seg != null && preferredRouteId !in seg.routes) {
                    0.25
                } else {
                    0.0
                }
                val nextCost = currentCost + edge.costMiles + transferPenalty + routePenalty
                if (nextCost < dist.getValue(edge.toStationId)) {
                    dist[edge.toStationId] = nextCost
                    prev[edge.toStationId] = PathStep(current, edge.toStationId, seg)
                    if (edge.toStationId !in queue) {
                        queue.add(edge.toStationId)
                    }
                }
            }
        }

        val path = mutableListOf<PathStep>()
        var curr: String? = endStationId
        while (curr != null && curr != startStationId) {
            val step = prev[curr] ?: return emptyList()
            path.add(0, step)
            curr = step.fromStationId
        }
        return path
    }
}
