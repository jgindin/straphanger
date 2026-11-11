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

        // Split into legs wherever the path changes trains: a route change at a station, or a walk
        // between platforms of a station complex.
        val legRequests = mutableListOf<TripLegRequest>()
        var legStart: String? = null
        var legEnd: String? = null
        var legRoute: String? = null
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
            legRoute = null
            legRoutes = emptySet()
        }

        for (step in rawPath) {
            val seg = step.segment
            if (seg == null) {
                closeLeg()
                continue
            }
            if (legStart != null && step.routeId != legRoute) {
                closeLeg()
            }
            if (legStart == null) {
                legStart = step.fromStationId
                legRoute = step.routeId
                legRoutes = seg.routes.toSet()
            } else {
                legRoutes = legRoutes intersect seg.routes.toSet()
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
        val segment: SubwaySegment?,
        /** The route ridden on this step; null for a walk between platforms. */
        val routeId: String? = null
    )

    /**
     * A search state: at [stationId], either riding [routeId] or on foot ([routeId] == null).
     * On foot is where every trip starts and ends, and where transfers happen.
     */
    private data class RouteState(val stationId: String, val routeId: String?)

    private class StateQueue {
        private val heap = mutableListOf<Pair<Double, RouteState>>()

        fun isNotEmpty(): Boolean = heap.isNotEmpty()

        fun push(cost: Double, state: RouteState) {
            heap.add(cost to state)
            var i = heap.lastIndex
            while (i > 0) {
                val parent = (i - 1) / 2
                if (heap[parent].first <= heap[i].first) break
                heap[i] = heap[parent].also { heap[parent] = heap[i] }
                i = parent
            }
        }

        fun pop(): Pair<Double, RouteState> {
            val top = heap.first()
            val last = heap.removeAt(heap.lastIndex)
            if (heap.isNotEmpty()) {
                heap[0] = last
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    val r = l + 1
                    var smallest = i
                    if (l < heap.size && heap[l].first < heap[smallest].first) smallest = l
                    if (r < heap.size && heap[r].first < heap[smallest].first) smallest = r
                    if (smallest == i) break
                    heap[i] = heap[smallest].also { heap[smallest] = heap[i] }
                    i = smallest
                }
            }
            return top
        }
    }

    /**
     * Route-aware shortest path. Riding a segment on a route costs its miles; getting off a train
     * (to change routes at the same station, or to walk to another platform) costs
     * [ROUTE_CHANGE_COST_MILES]; walking between platforms of a complex costs its edge plus
     * [PLATFORM_WALK_PENALTY_MILES]. So a one-seat ride wins unless a transfer saves more than a
     * mile. Trips start and end on foot; the final alighting cost is the same for every path.
     */
    private fun findShortestPath(
        startStationId: String,
        endStationId: String,
        preferredRouteId: String?
    ): List<PathStep> {
        if (startStationId == endStationId) return emptyList()
        val start = RouteState(startStationId, null)
        val goal = RouteState(endStationId, null)
        val dist = mutableMapOf(start to 0.0)
        // How each state was reached: the previous state and the station-to-station step, if any.
        val prev = mutableMapOf<RouteState, Pair<RouteState, PathStep?>>()
        val settled = mutableSetOf<RouteState>()
        val queue = StateQueue()
        queue.push(0.0, start)

        fun relax(from: RouteState, to: RouteState, cost: Double, step: PathStep?) {
            if (cost < (dist[to] ?: Double.POSITIVE_INFINITY)) {
                dist[to] = cost
                prev[to] = from to step
                queue.push(cost, to)
            }
        }

        while (queue.isNotEmpty()) {
            val (cost, state) = queue.pop()
            if (!settled.add(state)) continue
            if (state == goal) break
            val edges = adjacency[state.stationId].orEmpty()
            val ridingRoute = state.routeId
            if (ridingRoute == null) {
                // Board any route serving this station.
                val boardable = edges.mapNotNullTo(mutableSetOf()) { e -> e.segment?.routes }.flatten().toSet()
                for (r in boardable) relax(state, RouteState(state.stationId, r), cost, null)
                // Walk to another platform of the same complex.
                for (e in edges) {
                    if (e.segment != null) continue
                    relax(
                        state,
                        RouteState(e.toStationId, null),
                        cost + e.costMiles + PLATFORM_WALK_PENALTY_MILES,
                        PathStep(state.stationId, e.toStationId, null, null)
                    )
                }
            } else {
                // Stay on the train.
                for (e in edges) {
                    val seg = e.segment ?: continue
                    if (ridingRoute !in seg.routes) continue
                    val routePenalty = if (preferredRouteId != null && ridingRoute != preferredRouteId) 0.25 else 0.0
                    relax(
                        state,
                        RouteState(e.toStationId, ridingRoute),
                        cost + e.costMiles + routePenalty,
                        PathStep(state.stationId, e.toStationId, seg, ridingRoute)
                    )
                }
                // Get off.
                relax(state, RouteState(state.stationId, null), cost + ROUTE_CHANGE_COST_MILES, null)
            }
        }

        if (goal !in prev) return emptyList()
        val path = mutableListOf<PathStep>()
        var curr = goal
        while (curr != start) {
            val (before, step) = prev[curr] ?: return emptyList()
            if (step != null) path.add(0, step)
            curr = before
        }
        return path
    }

    private companion object {
        /** Mile-equivalent cost of getting off one train to take another. */
        const val ROUTE_CHANGE_COST_MILES = 1.0

        /** Extra mile-equivalent cost of walking between platforms, on top of the route change. */
        const val PLATFORM_WALK_PENALTY_MILES = 0.35
    }
}
