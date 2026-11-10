package com.straphanger.app.data.gtfs

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

class SubwayGraphEngine(
    val network: SubwayNetworkData = NycSubwayNetworkCatalog.defaultNetwork
) {
    private val stationById: Map<String, SubwayStation> = network.stations.associateBy { it.id }
    private val stationsByComplex: Map<String, List<SubwayStation>> = network.stations.groupBy { it.complexId }
    private val routeById: Map<String, SubwayRouteInfo> = network.routes.associateBy { it.routeId }
    private val segmentById: Map<String, SubwaySegment> = network.segments.associateBy { it.segmentId }

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
        val q = query.trim().lowercase()
        if (q.isEmpty()) return network.stations
        return network.stations.filter {
            it.name.lowercase().contains(q) ||
                it.complexName.lowercase().contains(q) ||
                it.borough.displayName.lowercase().contains(q) ||
                it.daytimeRoutes.any { r -> r.lowercase() == q }
        }
    }

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
     * if the shortest path crosses multiple trunk lines.
     */
    fun resolveOriginToDestinationAuto(
        originStationId: String,
        destinationStationId: String,
        preferredRouteId: String? = null,
        forceExpress: Boolean? = null,
        timestampEpochMs: Long = 1731164400000L, // Default daytime timestamp
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

        // Split into contiguous trunk legs if the path crosses complexes onto a new trunk
        val legRequests = mutableListOf<TripLegRequest>()
        var currentTrunk = segmentsOnly.first().trunkId
        var legStartStation = findEndpointStationForSegment(rawPath, 0, isStart = true)
        var lastStationOnTrunk = findEndpointStationForSegment(rawPath, 0, isStart = false)

        for (step in rawPath) {
            val seg = step.segment ?: continue
            if (seg.trunkId != currentTrunk) {
                legRequests.add(
                    TripLegRequest(
                        fromStationId = legStartStation,
                        toStationId = lastStationOnTrunk,
                        preferredRouteId = preferredRouteId?.takeIf { it in segmentsOnly.first().routes },
                        forceExpress = forceExpress
                    )
                )
                currentTrunk = seg.trunkId
                legStartStation = if (seg.fromStationId == step.toStationId) seg.toStationId else seg.fromStationId
            }
            lastStationOnTrunk = step.toStationId
        }

        legRequests.add(
            TripLegRequest(
                fromStationId = legStartStation,
                toStationId = lastStationOnTrunk,
                preferredRouteId = preferredRouteId,
                forceExpress = forceExpress
            )
        )

        return resolveTrip(
            legs = legRequests,
            timestampEpochMs = timestampEpochMs,
            actualDurationMinutes = actualDurationMinutes
        )
    }

    private fun findEndpointStationForSegment(
        steps: List<PathStep>,
        firstSegIndex: Int,
        isStart: Boolean
    ): String {
        val firstWithSeg = steps.first { it.segment != null }
        return if (isStart) firstWithSeg.fromStationId else firstWithSeg.toStationId
    }

    /**
     * Resolves an explicit 1..N leg trip (supporting multi-leg transfers that count as 1 ride).
     */
    fun resolveTrip(
        legs: List<TripLegRequest>,
        timestampEpochMs: Long = 1731164400000L,
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

            val startStation = stationById[orderedStations.first()]
            val endStation = stationById[orderedStations.last()]
            val candidateRoutesOnCorridor = segments
                .map { it.routes.toSet() }
                .reduceOrNull { acc, set -> acc.intersect(set) }
                ?.toList()
                .orEmpty()
                .ifEmpty { segments.first().routes }

            val legDistance = (segments.sumOf { it.distanceMiles } * 100.0).roundToInt() / 100.0

            // Check if both Express and Local candidates exist on this leg
            val expressRoutesForEnds = candidateRoutesOnCorridor.filter { r ->
                val info = routeById[r]
                val endSupportsExpress = startStation != null && endStation != null &&
                    (r in startStation.expressRoutes || startStation.expressRoutes.isNotEmpty()) &&
                    (r in endStation.expressRoutes || endStation.expressRoutes.isNotEmpty())
                val lateNightForcedLocal = servicePeriod == ServicePeriod.LATE_NIGHT &&
                    info?.lateNightBehavior == LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT
                info?.isExpressCapable == true && endSupportsExpress && !lateNightForcedLocal
            }

            val localRoutesForEnds = candidateRoutesOnCorridor.filter { r ->
                val info = routeById[r]
                info?.isExpressCapable == false ||
                    r in (startStation?.daytimeRoutes.orEmpty()) ||
                    (servicePeriod == ServicePeriod.LATE_NIGHT && info?.lateNightBehavior == LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT)
            }.ifEmpty { candidateRoutesOnCorridor }

            val hasIntermediateLocalOnlyStations = orderedStations.drop(1).dropLast(1).any { stId ->
                val st = stationById[stId]
                st != null && st.expressRoutes.isEmpty()
            }

            val expressStopCount = orderedStations.count { stId ->
                stId == orderedStations.first() ||
                    stId == orderedStations.last() ||
                    stationById[stId]?.expressRoutes?.isNotEmpty() == true
            }
            val localStopCount = orderedStations.size

            val estExpressMinutes = ((legDistance / 24.0) * 60.0 + (expressStopCount - 1) * 0.45)
            val estLocalMinutes = ((legDistance / 15.5) * 60.0 + (localStopCount - 1) * 0.55)

            // Determine whether Express or Local was ridden
            val chosenRouteId: String
            val chosenIsExpress: Boolean

            if (legReq.preferredRouteId != null && legReq.preferredRouteId in candidateRoutesOnCorridor) {
                chosenRouteId = legReq.preferredRouteId
                val rInfo = routeById[chosenRouteId]
                val lateNightLocal = servicePeriod == ServicePeriod.LATE_NIGHT &&
                    rInfo?.lateNightBehavior == LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT
                chosenIsExpress = legReq.forceExpress ?: (
                    rInfo?.isExpressCapable == true &&
                        !lateNightLocal &&
                        chosenRouteId in expressRoutesForEnds &&
                        hasIntermediateLocalOnlyStations
                    )
            } else if (legReq.forceExpress != null) {
                chosenIsExpress = legReq.forceExpress && expressRoutesForEnds.isNotEmpty()
                chosenRouteId = if (chosenIsExpress) {
                    expressRoutesForEnds.first()
                } else {
                    localRoutesForEnds.firstOrNull { routeById[it]?.isExpressCapable == false }
                        ?: localRoutesForEnds.first()
                }
            } else {
                // Auto-infer from actualDurationMinutes or default service period rules
                if (expressRoutesForEnds.isNotEmpty() && hasIntermediateLocalOnlyStations) {
                    if (legs.size == 1 && legReq.preferredRouteId == null) {
                        anyAmbiguous = true
                        val expRoute = expressRoutesForEnds.first()
                        val locRoute = localRoutesForEnds.firstOrNull { routeById[it]?.isExpressCapable == false }
                            ?: localRoutesForEnds.first()
                        allCandidates.add(
                            AmbiguousRouteCandidate(
                                routeId = expRoute,
                                isExpress = true,
                                label = "$expRoute Express",
                                estimatedMinutes = (estExpressMinutes * 10.0).roundToInt() / 10.0,
                                stoppedStationCount = expressStopCount
                            )
                        )
                        allCandidates.add(
                            AmbiguousRouteCandidate(
                                routeId = locRoute,
                                isExpress = false,
                                label = "$locRoute Local",
                                estimatedMinutes = (estLocalMinutes * 10.0).roundToInt() / 10.0,
                                stoppedStationCount = localStopCount
                            )
                        )
                    }
                    val preferLocalByDuration = actualDurationMinutes != null &&
                        abs(actualDurationMinutes - estLocalMinutes) < abs(actualDurationMinutes - estExpressMinutes)
                    if (preferLocalByDuration) {
                        chosenIsExpress = false
                        chosenRouteId = localRoutesForEnds.firstOrNull { routeById[it]?.isExpressCapable == false }
                            ?: localRoutesForEnds.first()
                    } else {
                        chosenIsExpress = true
                        chosenRouteId = expressRoutesForEnds.first()
                    }
                } else {
                    chosenIsExpress = false
                    chosenRouteId = localRoutesForEnds.first()
                }
            }

            val stoppedStations = mutableListOf<String>()
            val skippedStations = mutableListOf<String>()
            for ((idx, stId) in orderedStations.withIndex()) {
                val isEndpoint = idx == 0 || idx == orderedStations.lastIndex
                val st = stationById[stId]
                val stopsHere = if (isEndpoint || !chosenIsExpress) {
                    true
                } else {
                    st != null && (chosenRouteId in st.expressRoutes || st.expressRoutes.isNotEmpty())
                }
                if (stopsHere) {
                    stoppedStations.add(stId)
                } else {
                    skippedStations.add(stId)
                }
            }

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
        val prevTrunk = mutableMapOf<String, String?>()
        val visited = mutableSetOf<String>()

        dist[startStationId] = 0.0
        val queue = mutableListOf(startStationId)

        while (queue.isNotEmpty()) {
            queue.sortBy { dist.getValue(it) }
            val current = queue.removeAt(0)
            if (current == endStationId) break
            if (!visited.add(current)) continue

            val currentCost = dist.getValue(current)
            val currentTrunkId = prevTrunk[current]

            for (edge in adjacency[current].orEmpty()) {
                if (edge.toStationId in visited) continue
                val seg = edge.segment
                val trunkSwitchPenalty = if (seg == null) {
                    0.35 // In-complex platform transfer penalty so single-seat rides win
                } else if (currentTrunkId != null && currentTrunkId != seg.trunkId) {
                    0.35
                } else {
                    0.0
                }
                val routePenalty = if (preferredRouteId != null && seg != null && preferredRouteId !in seg.routes) {
                    0.25
                } else {
                    0.0
                }
                val nextCost = currentCost + edge.costMiles + trunkSwitchPenalty + routePenalty
                if (nextCost < dist.getValue(edge.toStationId)) {
                    dist[edge.toStationId] = nextCost
                    prev[edge.toStationId] = PathStep(current, edge.toStationId, seg)
                    prevTrunk[edge.toStationId] = seg?.trunkId ?: currentTrunkId
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
