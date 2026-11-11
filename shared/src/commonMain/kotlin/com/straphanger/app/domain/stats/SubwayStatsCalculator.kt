package com.straphanger.app.domain.stats

import com.straphanger.app.data.gtfs.Borough
import com.straphanger.app.data.gtfs.ServicePeriod
import com.straphanger.app.data.gtfs.StopRole
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayRouteInfo
import com.straphanger.app.data.local.CompleteRideRecord
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class StationGroupingMode(val label: String, val officialTotal: Int) {
    INDIVIDUAL_472("472 Stations", 472),
    COMPLEXES_424("424 Complexes", 424)
}

@Serializable
enum class StationVisitFilterMode(val label: String) {
    STOPPED_AT("All Stopped At"),
    BOARDED_OR_EXITED_ONLY("Boarded / Exited Only")
}

@Serializable
data class LineCompletionStat(
    val routeInfo: SubwayRouteInfo,
    val legRideCount: Int,
    val totalMilesRidden: Double,
    val uniqueCorridorMilesRidden: Double,
    val totalCorridorMilesAvailable: Double,
    val completionPercent: Double
)

@Serializable
data class BoroughStat(
    val borough: Borough,
    val stationsVisited: Int,
    val totalStationsInGraph: Int,
    val milesRidden: Double,
    val rideTouches: Int
)

@Serializable
data class FavoriteStationStat(
    val stationId: String,
    val stationName: String,
    val borough: Borough,
    val visitCount: Int
)

@Serializable
data class RoutePairStat(
    val originName: String,
    val destinationName: String,
    val rideCount: Int
)

@Serializable
data class MonthlyTrendBucket(
    val yearMonth: String,
    val rideCount: Int,
    val totalMiles: Double,
    val newUniqueMiles: Double
)

@Serializable
data class SubwayAchievement(
    val id: String,
    val title: String,
    val description: String,
    val iconCode: String,
    val currentValue: Double,
    val targetValue: Double,
    val isUnlocked: Boolean
) {
    val progressFraction: Float
        get() = if (targetValue <= 0.0) 1f else (currentValue / targetValue).toFloat().coerceIn(0f, 1f)
}

@Serializable
data class SubwayStatsSnapshot(
    val totalRides: Int,
    val trackingStartEpochMs: Long?,
    val totalMilesRidden: Double,
    val uniqueCorridorMilesRidden: Double,
    val totalGraphCorridorMiles: Double,
    val scaledUniqueSystemMiles: Double,
    val systemCompletionPercent: Double,
    val totalHoursUnderground: Double,
    val longestRideMiles: Double,
    val shortestRideMiles: Double,
    val pendingReviewCount: Int,

    // Station counts for all 4 combinations of (472 vs 424) x (Stopped-At vs Boarded/Exited)
    val stoppedAtIndividualStationsCount: Int,
    val stoppedAtComplexesCount: Int,
    val boardedExitedIndividualStationsCount: Int,
    val boardedExitedComplexesCount: Int,
    val visitedStationIdsStoppedAt: Set<String>,
    val visitedStationIdsBoardedExited: Set<String>,

    // Map coloring & heatmap indices
    val segmentRideCounts: Map<String, Int>,
    val segmentDominantRoutes: Map<String, String>,
    val segmentRoutesRidden: Map<String, Set<String>>,

    // Detailed breakdowns
    val lineCompletionStats: List<LineCompletionStat>,
    val boroughStats: List<BoroughStat>,
    val topStations: List<FavoriteStationStat>,
    val topRoutePairs: List<RoutePairStat>,
    val monthlyTrends: List<MonthlyTrendBucket>,
    val achievements: List<SubwayAchievement>
) {
    fun getDisplayedStationCount(
        groupingMode: StationGroupingMode,
        visitFilterMode: StationVisitFilterMode
    ): Int {
        return when (groupingMode) {
            StationGroupingMode.INDIVIDUAL_472 -> when (visitFilterMode) {
                StationVisitFilterMode.STOPPED_AT -> stoppedAtIndividualStationsCount
                StationVisitFilterMode.BOARDED_OR_EXITED_ONLY -> boardedExitedIndividualStationsCount
            }
            StationGroupingMode.COMPLEXES_424 -> when (visitFilterMode) {
                StationVisitFilterMode.STOPPED_AT -> stoppedAtComplexesCount
                StationVisitFilterMode.BOARDED_OR_EXITED_ONLY -> boardedExitedComplexesCount
            }
        }
    }
}

class SubwayStatsCalculator(
    private val graphEngine: SubwayGraphEngine
) {
    fun computeStats(rides: List<CompleteRideRecord>): SubwayStatsSnapshot {
        val network = graphEngine.network
        val totalGraphMiles = network.segments.sumOf { it.distanceMiles }.coerceAtLeast(1.0)

        val totalRides = rides.size
        val trackingStart = rides.minOfOrNull { it.ride.startTimeEpochMs }
        val totalMiles = round2(rides.sumOf { it.ride.totalDistanceMiles })
        val totalHours = round2(rides.sumOf { it.ride.durationMinutes } / 60.0)
        val longestRide = round2(rides.maxOfOrNull { it.ride.totalDistanceMiles } ?: 0.0)
        val shortestRide = round2(rides.minOfOrNull { it.ride.totalDistanceMiles } ?: 0.0)
        val pendingReviews = rides.count { it.ride.needsReview }

        // Segment frequency & unique physical corridor coverage
        val segRideCounts = mutableMapOf<String, Int>()
        val segRouteCounts = mutableMapOf<String, MutableMap<String, Int>>()
        val segRoutesSet = mutableMapOf<String, MutableSet<String>>()

        for (rec in rides) {
            // Count each segment once per ride for heatmap frequency
            val seenSegInRide = mutableSetOf<String>()
            for (seg in rec.segments) {
                if (seenSegInRide.add(seg.segmentId)) {
                    segRideCounts[seg.segmentId] = (segRideCounts[seg.segmentId] ?: 0) + 1
                }
                val routeMap = segRouteCounts.getOrPut(seg.segmentId) { mutableMapOf() }
                routeMap[seg.routeId] = (routeMap[seg.routeId] ?: 0) + 1
                segRoutesSet.getOrPut(seg.segmentId) { mutableSetOf() }.add(seg.routeId)
            }
        }

        val segDominantRoutes = segRouteCounts.mapValues { (_, rCounts) ->
            rCounts.maxByOrNull { it.value }?.key.orEmpty()
        }

        val uniqueCorridorMiles = round2(
            segRideCounts.keys.sumOf { segId ->
                graphEngine.getSegment(segId)?.distanceMiles ?: 0.0
            }
        )

        val completionFraction = (uniqueCorridorMiles / totalGraphMiles).coerceIn(0.0, 1.0)
        val systemCompletionPct = round2(completionFraction * 100.0)
        val scaledUniqueSystemMiles = round2(uniqueCorridorMiles)

        // Station coverage sets
        val stoppedStationIds = mutableSetOf<String>()
        val stoppedComplexIds = mutableSetOf<String>()
        val boardedExitedStationIds = mutableSetOf<String>()
        val boardedExitedComplexIds = mutableSetOf<String>()
        val stationBoardExitCount = mutableMapOf<String, Int>()

        for (rec in rides) {
            for (stop in rec.stationStops) {
                stoppedStationIds.add(stop.stationId)
                stoppedComplexIds.add(stop.complexId)
                if (stop.stopRole != StopRole.STOPPED_THROUGH) {
                    boardedExitedStationIds.add(stop.stationId)
                    boardedExitedComplexIds.add(stop.complexId)
                    stationBoardExitCount[stop.stationId] = (stationBoardExitCount[stop.stationId] ?: 0) + 1
                }
            }
        }

        // Per-Line completion stats
        val lineStats = network.routes.map { routeInfo ->
            val rId = routeInfo.routeId
            val routeSegmentsInGraph = network.segments.filter { rId in it.routes }
            val routeAvailableMiles = round2(
                routeSegmentsInGraph.sumOf { it.distanceMiles }.coerceAtLeast(0.5)
            )
            val legsOnRoute = rides.flatMap { it.legs }.filter { it.routeId == rId }
            val totalMilesOnRoute = round2(legsOnRoute.sumOf { it.distanceMiles })
            val uniqueSegIdsOnRoute = rides.flatMap { it.segments }
                .filter { it.routeId == rId }
                .map { it.segmentId }
                .toSet()
            val uniqueMilesOnRoute = round2(
                uniqueSegIdsOnRoute.sumOf { graphEngine.getSegment(it)?.distanceMiles ?: 0.0 }
            )
            val routeCompletionPct = round2(
                ((uniqueMilesOnRoute / routeAvailableMiles) * 100.0).coerceIn(0.0, 100.0)
            )

            LineCompletionStat(
                routeInfo = routeInfo,
                legRideCount = legsOnRoute.size,
                totalMilesRidden = totalMilesOnRoute,
                uniqueCorridorMilesRidden = uniqueMilesOnRoute,
                totalCorridorMilesAvailable = routeAvailableMiles,
                completionPercent = routeCompletionPct
            )
        }

        // Borough breakdown
        val boroughStats = Borough.entries.map { boro ->
            val stationsInBoro = network.stations.filter { it.borough == boro }
            val visitedInBoro = stationsInBoro.count { it.id in stoppedStationIds }
            var boroMiles = 0.0
            var boroTouches = 0
            for (rec in rides) {
                var touchedThisRide = false
                for (seg in rec.segments) {
                    val fromBoro = graphEngine.getStation(
                        graphEngine.getSegment(seg.segmentId)?.fromStationId.orEmpty()
                    )?.borough
                    if (fromBoro == boro) {
                        boroMiles += seg.distanceMiles
                        touchedThisRide = true
                    }
                }
                if (touchedThisRide) boroTouches++
            }
            BoroughStat(
                borough = boro,
                stationsVisited = visitedInBoro,
                totalStationsInGraph = stationsInBoro.size,
                milesRidden = round2(boroMiles),
                rideTouches = boroTouches
            )
        }

        // Top Stations & Top O-D Pairs
        val topStations = stationBoardExitCount.entries
            .sortedByDescending { it.value }
            .take(5)
            .mapNotNull { (stId, cnt) ->
                val st = graphEngine.getStation(stId) ?: return@mapNotNull null
                FavoriteStationStat(
                    stationId = stId,
                    stationName = st.name,
                    borough = st.borough,
                    visitCount = cnt
                )
            }

        val pairCounts = mutableMapOf<Pair<String, String>, Int>()
        for (rec in rides) {
            val origName = graphEngine.getStation(rec.ride.originStationId)?.name ?: rec.ride.originStationId
            val destName = graphEngine.getStation(rec.ride.destinationStationId)?.name ?: rec.ride.destinationStationId
            val key = origName to destName
            pairCounts[key] = (pairCounts[key] ?: 0) + 1
        }
        val topRoutePairs = pairCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { (pair, cnt) ->
                RoutePairStat(
                    originName = pair.first,
                    destinationName = pair.second,
                    rideCount = cnt
                )
            }

        // Monthly Trends (chronological)
        val chronologicalRides = rides.sortedBy { it.ride.startTimeEpochMs }
        val seenSegmentsEver = mutableSetOf<String>()
        val monthlyMap = linkedMapOf<String, Triple<Int, Double, Double>>()

        for (rec in chronologicalRides) {
            val dt = Instant.fromEpochMilliseconds(rec.ride.startTimeEpochMs)
                .toLocalDateTime(TimeZone.of("America/New_York"))
            val ym = "${dt.year}-${dt.monthNumber.toString().padStart(2, '0')}"
            var newlyUnlockedMiles = 0.0
            for (seg in rec.segments) {
                if (seenSegmentsEver.add(seg.segmentId)) {
                    newlyUnlockedMiles += seg.distanceMiles
                }
            }
            val prev = monthlyMap[ym] ?: Triple(0, 0.0, 0.0)
            monthlyMap[ym] = Triple(
                prev.first + 1,
                prev.second + rec.ride.totalDistanceMiles,
                prev.third + newlyUnlockedMiles
            )
        }

        val monthlyTrends = monthlyMap.map { (ym, triple) ->
            MonthlyTrendBucket(
                yearMonth = ym,
                rideCount = triple.first,
                totalMiles = round2(triple.second),
                newUniqueMiles = round2(triple.third)
            )
        }

        val achievements = AchievementEngine.evaluateAchievements(
            rides = rides,
            uniqueCorridorMiles = uniqueCorridorMiles,
            lineStats = lineStats,
            boroughStats = boroughStats
        )

        return SubwayStatsSnapshot(
            totalRides = totalRides,
            trackingStartEpochMs = trackingStart,
            totalMilesRidden = totalMiles,
            uniqueCorridorMilesRidden = uniqueCorridorMiles,
            totalGraphCorridorMiles = round2(totalGraphMiles),
            scaledUniqueSystemMiles = scaledUniqueSystemMiles,
            systemCompletionPercent = systemCompletionPct,
            totalHoursUnderground = totalHours,
            longestRideMiles = longestRide,
            shortestRideMiles = shortestRide,
            pendingReviewCount = pendingReviews,
            stoppedAtIndividualStationsCount = stoppedStationIds.size,
            stoppedAtComplexesCount = stoppedComplexIds.size,
            boardedExitedIndividualStationsCount = boardedExitedStationIds.size,
            boardedExitedComplexesCount = boardedExitedComplexIds.size,
            visitedStationIdsStoppedAt = stoppedStationIds,
            visitedStationIdsBoardedExited = boardedExitedStationIds,
            segmentRideCounts = segRideCounts,
            segmentDominantRoutes = segDominantRoutes,
            segmentRoutesRidden = segRoutesSet.mapValues { it.value.toSet() },
            lineCompletionStats = lineStats,
            boroughStats = boroughStats,
            topStations = topStations,
            topRoutePairs = topRoutePairs,
            monthlyTrends = monthlyTrends,
            achievements = achievements
        )
    }

    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
}

object AchievementEngine {
    fun evaluateAchievements(
        rides: List<CompleteRideRecord>,
        uniqueCorridorMiles: Double,
        lineStats: List<LineCompletionStat>,
        boroughStats: List<BoroughStat>
    ): List<SubwayAchievement> {
        val totalRides = rides.size.toDouble()
        val mainlandBoroughsVisited = boroughStats
            .filter { it.borough != Borough.STATEN_ISLAND }
            .count { it.stationsVisited > 0 }
            .toDouble()
        val expressLegs = rides.flatMap { it.legs }.count { it.isExpress }.toDouble()
        val completedLines = lineStats.count { it.completionPercent >= 99.0 }.toDouble()
        val shuttleRides = rides.flatMap { it.legs }
            .count { it.routeId in setOf("S", "SF", "SR") }
            .toDouble()
        val rushPeakRides = rides.count { rec ->
            rec.legs.any { it.servicePeriod == ServicePeriod.RUSH_PEAK }
        }.toDouble()
        val lateNightRides = rides.count { rec ->
            rec.legs.any { it.servicePeriod == ServicePeriod.LATE_NIGHT }
        }.toDouble()

        return listOf(
            SubwayAchievement(
                id = "FIRST_SWIPE",
                title = "First Swipe",
                description = "Log your first NYC Subway ride",
                iconCode = "OMNY",
                currentValue = totalRides,
                targetValue = 1.0,
                isUnlocked = totalRides >= 1.0
            ),
            SubwayAchievement(
                id = "BOROUGH_EXPLORER",
                title = "Four-Borough Straphanger",
                description = "Visit stations across Manhattan, Brooklyn, Queens, and The Bronx",
                iconCode = "NYC4",
                currentValue = mainlandBoroughsVisited,
                targetValue = 4.0,
                isUnlocked = mainlandBoroughsVisited >= 4.0
            ),
            SubwayAchievement(
                id = "EXPRESS_MASTER",
                title = "Express Track Master",
                description = "Ride 3 or more Express subway legs skipping local stops",
                iconCode = "EXP",
                currentValue = expressLegs,
                targetValue = 3.0,
                isUnlocked = expressLegs >= 3.0
            ),
            SubwayAchievement(
                id = "LINE_COMPLETIONIST",
                title = "End-to-End Line Finisher",
                description = "Complete 100% of the corridor segments on any NYC Subway line",
                iconCode = "100%",
                currentValue = completedLines,
                targetValue = 1.0,
                isUnlocked = completedLines >= 1.0
            ),
            SubwayAchievement(
                id = "MILEAGE_EXPLORER",
                title = "25 Unique System Miles",
                description = "Unlock 25+ unique miles of physical NYC Subway track",
                iconCode = "25MI",
                currentValue = uniqueCorridorMiles,
                targetValue = 25.0,
                isUnlocked = uniqueCorridorMiles >= 25.0
            ),
            SubwayAchievement(
                id = "SHUTTLE_COLLECTOR",
                title = "Shuttle Master",
                description = "Ride the 42 St, Franklin Ave, or Rockaway Park Shuttle (S)",
                iconCode = "S",
                currentValue = shuttleRides,
                targetValue = 1.0,
                isUnlocked = shuttleRides >= 1.0
            ),
            SubwayAchievement(
                id = "RUSH_HOUR_WARRIOR",
                title = "Rush Hour Warrior",
                description = "Complete 5+ rides during weekday Rush Peak hours",
                iconCode = "PEAK",
                currentValue = rushPeakRides,
                targetValue = 5.0,
                isUnlocked = rushPeakRides >= 5.0
            ),
            SubwayAchievement(
                id = "LATE_NIGHT_OWL",
                title = "Night Owl Local",
                description = "Take a late-night subway ride between 11:45 PM and 5:30 AM",
                iconCode = "OWL",
                currentValue = lateNightRides,
                targetValue = 1.0,
                isUnlocked = lateNightRides >= 1.0
            )
        )
    }
}
