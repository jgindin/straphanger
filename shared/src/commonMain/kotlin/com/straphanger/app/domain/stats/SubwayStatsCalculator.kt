package com.straphanger.app.domain.stats

import com.straphanger.app.data.gtfs.Borough
import com.straphanger.app.data.gtfs.ServicePeriod
import com.straphanger.app.data.gtfs.StopRole
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayRouteInfo
import com.straphanger.app.data.local.CompleteRideRecord
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class StationGroupingMode(val label: String) {
  INDIVIDUAL_STATIONS("Stations"),
  STATION_COMPLEXES("Complexes"),
}

@Serializable
enum class StationVisitFilterMode(val label: String) {
  STOPPED_AT("All Stopped At"),
  BOARDED_OR_EXITED_ONLY("Boarded / Exited Only"),
}

@Serializable
data class LineCompletionStat(
  val routeInfo: SubwayRouteInfo,
  val legRideCount: Int,
  val totalMilesRidden: Double,
  val uniqueCorridorMilesRidden: Double,
  val totalCorridorMilesAvailable: Double,
  val completionPercent: Double,
)

@Serializable
data class BoroughStat(
  val borough: Borough,
  val stationsVisited: Int,
  val totalStationsInGraph: Int,
  val milesRidden: Double,
  val rideTouches: Int,
)

@Serializable
data class FavoriteStationStat(
  val stationId: String,
  val stationName: String,
  val borough: Borough,
  val visitCount: Int,
)

@Serializable
data class RoutePairStat(
  val originName: String,
  val destinationName: String,
  val rideCount: Int,
)

@Serializable
data class MonthlyTrendBucket(
  val yearMonth: String,
  val rideCount: Int,
  val totalMiles: Double,
  val newUniqueMiles: Double,
)

@Serializable
data class SubwayAchievement(
  val id: String,
  val title: String,
  val description: String,
  val iconCode: String,
  val currentValue: Double,
  val targetValue: Double,
  val isUnlocked: Boolean,
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

  // Totals in the loaded network
  val totalStationsInNetwork: Int,
  val totalComplexesInNetwork: Int,

  // Station counts for all 4 combinations of (stations vs complexes) x (Stopped-At vs
  // Boarded/Exited)
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
  val achievements: List<SubwayAchievement>,
) {
  fun getDisplayedStationCount(
    groupingMode: StationGroupingMode,
    visitFilterMode: StationVisitFilterMode,
  ): Int {
    return when (groupingMode) {
      StationGroupingMode.INDIVIDUAL_STATIONS ->
        when (visitFilterMode) {
          StationVisitFilterMode.STOPPED_AT -> stoppedAtIndividualStationsCount
          StationVisitFilterMode.BOARDED_OR_EXITED_ONLY -> boardedExitedIndividualStationsCount
        }
      StationGroupingMode.STATION_COMPLEXES ->
        when (visitFilterMode) {
          StationVisitFilterMode.STOPPED_AT -> stoppedAtComplexesCount
          StationVisitFilterMode.BOARDED_OR_EXITED_ONLY -> boardedExitedComplexesCount
        }
    }
  }

  fun getDisplayedStationTotal(groupingMode: StationGroupingMode): Int =
    when (groupingMode) {
      StationGroupingMode.INDIVIDUAL_STATIONS -> totalStationsInNetwork
      StationGroupingMode.STATION_COMPLEXES -> totalComplexesInNetwork
    }
}

/** How often each segment was ridden, and on which routes. */
private class SegmentUsage(
  val rideCounts: Map<String, Int>,
  val dominantRoutes: Map<String, String>,
  val routesRidden: Map<String, Set<String>>,
)

/** The stations and complexes ridden through or boarded at and exited from. */
private class StationCoverage(
  val stoppedStationIds: Set<String>,
  val stoppedComplexIds: Set<String>,
  val boardedExitedStationIds: Set<String>,
  val boardedExitedComplexIds: Set<String>,
  val boardExitCounts: Map<String, Int>,
)

private class MonthTotals(val rideCount: Int, val miles: Double, val newUniqueMiles: Double)

private const val TOP_LIST_SIZE = 5

private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

class SubwayStatsCalculator(private val graphEngine: SubwayGraphEngine) {
  fun computeStats(rides: List<CompleteRideRecord>): SubwayStatsSnapshot {
    val network = graphEngine.network
    val totalGraphMiles = network.segments.sumOf { it.distanceMiles }.coerceAtLeast(1.0)
    val usage = segmentUsage(rides)
    val uniqueCorridorMiles =
      round2(
        usage.rideCounts.keys.sumOf { segId ->
          graphEngine.getSegment(segId)?.distanceMiles ?: 0.0
        },
      )
    val completionFraction = (uniqueCorridorMiles / totalGraphMiles).coerceIn(0.0, 1.0)
    val coverage = stationCoverage(rides)
    val lineStats = lineStats(rides)
    val boroughStats = boroughStats(rides, coverage.stoppedStationIds)

    return SubwayStatsSnapshot(
      totalRides = rides.size,
      trackingStartEpochMs = rides.minOfOrNull { it.ride.startTimeEpochMs },
      totalMilesRidden = round2(rides.sumOf { it.ride.totalDistanceMiles }),
      uniqueCorridorMilesRidden = uniqueCorridorMiles,
      totalGraphCorridorMiles = round2(totalGraphMiles),
      scaledUniqueSystemMiles = round2(uniqueCorridorMiles),
      systemCompletionPercent = round2(completionFraction * 100.0),
      totalHoursUnderground = round2(rides.sumOf { it.ride.durationMinutes } / 60.0),
      longestRideMiles = round2(rides.maxOfOrNull { it.ride.totalDistanceMiles } ?: 0.0),
      shortestRideMiles = round2(rides.minOfOrNull { it.ride.totalDistanceMiles } ?: 0.0),
      pendingReviewCount = rides.count { it.ride.needsReview },
      totalStationsInNetwork = network.stationCount,
      totalComplexesInNetwork = network.complexCount,
      stoppedAtIndividualStationsCount = coverage.stoppedStationIds.size,
      stoppedAtComplexesCount = coverage.stoppedComplexIds.size,
      boardedExitedIndividualStationsCount = coverage.boardedExitedStationIds.size,
      boardedExitedComplexesCount = coverage.boardedExitedComplexIds.size,
      visitedStationIdsStoppedAt = coverage.stoppedStationIds,
      visitedStationIdsBoardedExited = coverage.boardedExitedStationIds,
      segmentRideCounts = usage.rideCounts,
      segmentDominantRoutes = usage.dominantRoutes,
      segmentRoutesRidden = usage.routesRidden,
      lineCompletionStats = lineStats,
      boroughStats = boroughStats,
      topStations = topStations(coverage.boardExitCounts),
      topRoutePairs = topRoutePairs(rides),
      monthlyTrends = monthlyTrends(rides),
      achievements =
        AchievementEngine.evaluateAchievements(
          rides = rides,
          uniqueCorridorMiles = uniqueCorridorMiles,
          lineStats = lineStats,
          boroughStats = boroughStats,
        ),
    )
  }

  // Segment frequency & unique physical corridor coverage. Segments missing from the current
  // network (rides saved against older data) are skipped.
  private fun segmentUsage(rides: List<CompleteRideRecord>): SegmentUsage {
    val segRideCounts = mutableMapOf<String, Int>()
    val segRouteCounts = mutableMapOf<String, MutableMap<String, Int>>()
    val segRoutesSet = mutableMapOf<String, MutableSet<String>>()
    for (rec in rides) {
      // Count each segment once per ride for heatmap frequency
      val seenSegInRide = mutableSetOf<String>()
      for (seg in rec.segments.filter { graphEngine.getSegment(it.segmentId) != null }) {
        if (seenSegInRide.add(seg.segmentId)) {
          segRideCounts[seg.segmentId] = (segRideCounts[seg.segmentId] ?: 0) + 1
        }
        val routeMap = segRouteCounts.getOrPut(seg.segmentId) { mutableMapOf() }
        routeMap[seg.routeId] = (routeMap[seg.routeId] ?: 0) + 1
        segRoutesSet.getOrPut(seg.segmentId) { mutableSetOf() }.add(seg.routeId)
      }
    }
    return SegmentUsage(
      rideCounts = segRideCounts,
      dominantRoutes =
        segRouteCounts.mapValues { (_, rCounts) ->
          rCounts.maxByOrNull { it.value }?.key.orEmpty()
        },
      routesRidden = segRoutesSet.mapValues { it.value.toSet() },
    )
  }

  // Station IDs missing from the current network (rides saved against older data) are skipped.
  private fun stationCoverage(rides: List<CompleteRideRecord>): StationCoverage {
    val stoppedStationIds = mutableSetOf<String>()
    val stoppedComplexIds = mutableSetOf<String>()
    val boardedExitedStationIds = mutableSetOf<String>()
    val boardedExitedComplexIds = mutableSetOf<String>()
    val stationBoardExitCount = mutableMapOf<String, Int>()
    for (stop in rides.flatMap { it.stationStops }) {
      val station = graphEngine.getStation(stop.stationId)
      if (station != null) {
        stoppedStationIds.add(station.id)
        stoppedComplexIds.add(station.complexId)
        if (stop.stopRole != StopRole.STOPPED_THROUGH) {
          boardedExitedStationIds.add(station.id)
          boardedExitedComplexIds.add(station.complexId)
          stationBoardExitCount[stop.stationId] = (stationBoardExitCount[stop.stationId] ?: 0) + 1
        }
      }
    }
    return StationCoverage(
      stoppedStationIds,
      stoppedComplexIds,
      boardedExitedStationIds,
      boardedExitedComplexIds,
      stationBoardExitCount,
    )
  }

  private fun lineStats(rides: List<CompleteRideRecord>): List<LineCompletionStat> =
    graphEngine.network.routes.map { routeInfo ->
      val rId = routeInfo.routeId
      val routeAvailableMiles =
        round2(graphEngine.network.segments.filter { rId in it.routes }.sumOf { it.distanceMiles })
      val legsOnRoute = rides.flatMap { it.legs }.filter { it.routeId == rId }
      val uniqueSegIdsOnRoute =
        rides
          .flatMap { it.segments }
          .filter { it.routeId == rId && graphEngine.getSegment(it.segmentId) != null }
          .map { it.segmentId }
          .toSet()
      val uniqueMilesOnRoute =
        round2(uniqueSegIdsOnRoute.sumOf { graphEngine.getSegment(it)?.distanceMiles ?: 0.0 })
      LineCompletionStat(
        routeInfo = routeInfo,
        legRideCount = legsOnRoute.size,
        totalMilesRidden = round2(legsOnRoute.sumOf { it.distanceMiles }),
        uniqueCorridorMilesRidden = uniqueMilesOnRoute,
        totalCorridorMilesAvailable = routeAvailableMiles,
        completionPercent =
          if (routeAvailableMiles <= 0.0) 0.0
          else round2(((uniqueMilesOnRoute / routeAvailableMiles) * 100.0).coerceIn(0.0, 100.0)),
      )
    }

  private fun boroughStats(
    rides: List<CompleteRideRecord>,
    stoppedStationIds: Set<String>,
  ): List<BoroughStat> =
    Borough.entries.map { boro ->
      val stationsInBoro = graphEngine.network.stations.filter { it.borough == boro }
      // Each ridden segment counts toward the borough of the station it starts from.
      val milesPerRide = rides.map { rec ->
        rec.segments.filter { segmentBorough(it.segmentId) == boro }.map { it.distanceMiles }
      }
      BoroughStat(
        borough = boro,
        stationsVisited = stationsInBoro.count { it.id in stoppedStationIds },
        totalStationsInGraph = stationsInBoro.size,
        milesRidden = round2(milesPerRide.flatten().sum()),
        rideTouches = milesPerRide.count { it.isNotEmpty() },
      )
    }

  private fun segmentBorough(segmentId: String): Borough? =
    graphEngine.getStation(graphEngine.getSegment(segmentId)?.fromStationId.orEmpty())?.borough

  private fun topStations(boardExitCounts: Map<String, Int>): List<FavoriteStationStat> =
    boardExitCounts.entries
      .sortedByDescending { it.value }
      .take(TOP_LIST_SIZE)
      .mapNotNull { (stId, cnt) ->
        graphEngine.getStation(stId)?.let { st ->
          FavoriteStationStat(
            stationId = stId,
            stationName = st.name,
            borough = st.borough,
            visitCount = cnt,
          )
        }
      }

  private fun topRoutePairs(rides: List<CompleteRideRecord>): List<RoutePairStat> {
    val pairCounts = mutableMapOf<Pair<String, String>, Int>()
    for (rec in rides) {
      val origName =
        graphEngine.getStation(rec.ride.originStationId)?.name ?: rec.ride.originStationId
      val destName =
        graphEngine.getStation(rec.ride.destinationStationId)?.name ?: rec.ride.destinationStationId
      val key = origName to destName
      pairCounts[key] = (pairCounts[key] ?: 0) + 1
    }
    return pairCounts.entries
      .sortedByDescending { it.value }
      .take(TOP_LIST_SIZE)
      .map { (pair, cnt) ->
        RoutePairStat(originName = pair.first, destinationName = pair.second, rideCount = cnt)
      }
  }

  // Chronological; newUniqueMiles counts each segment in the month it was first ridden.
  private fun monthlyTrends(rides: List<CompleteRideRecord>): List<MonthlyTrendBucket> {
    val seenSegmentsEver = mutableSetOf<String>()
    val monthlyMap = linkedMapOf<String, MonthTotals>()
    for (rec in rides.sortedBy { it.ride.startTimeEpochMs }) {
      val dt =
        Instant.fromEpochMilliseconds(rec.ride.startTimeEpochMs)
          .toLocalDateTime(TimeZone.of("America/New_York"))
      val ym = "${dt.year}-${dt.month.number.toString().padStart(2, '0')}"
      var newlyUnlockedMiles = 0.0
      for (seg in rec.segments.filter { graphEngine.getSegment(it.segmentId) != null }) {
        if (seenSegmentsEver.add(seg.segmentId)) newlyUnlockedMiles += seg.distanceMiles
      }
      val prev = monthlyMap[ym] ?: MonthTotals(0, 0.0, 0.0)
      monthlyMap[ym] =
        MonthTotals(
          prev.rideCount + 1,
          prev.miles + rec.ride.totalDistanceMiles,
          prev.newUniqueMiles + newlyUnlockedMiles,
        )
    }
    return monthlyMap.map { (ym, totals) ->
      MonthlyTrendBucket(
        yearMonth = ym,
        rideCount = totals.rideCount,
        totalMiles = round2(totals.miles),
        newUniqueMiles = round2(totals.newUniqueMiles),
      )
    }
  }
}

/** The counts that achievements measure progress against. */
private class AchievementProgress(
  val totalRides: Double,
  val mainlandBoroughsVisited: Double,
  val expressLegs: Double,
  val completedLines: Double,
  val uniqueCorridorMiles: Double,
  val shuttleRides: Double,
  val rushPeakRides: Double,
  val lateNightRides: Double,
)

/** An achievement, unlocked once [current] reaches [targetValue]. */
private class AchievementDefinition(
  val id: String,
  val title: String,
  val description: String,
  val iconCode: String,
  val targetValue: Double,
  val current: (AchievementProgress) -> Double,
) {
  fun evaluate(progress: AchievementProgress): SubwayAchievement {
    val currentValue = current(progress)
    return SubwayAchievement(
      id = id,
      title = title,
      description = description,
      iconCode = iconCode,
      currentValue = currentValue,
      targetValue = targetValue,
      isUnlocked = currentValue >= targetValue,
    )
  }
}

/** A line counts as complete from this completion percentage. */
private const val LINE_COMPLETE_PERCENT = 99.0

private val ACHIEVEMENTS =
  listOf(
    AchievementDefinition(
      id = "FIRST_SWIPE",
      title = "First Swipe",
      description = "Log your first NYC Subway ride",
      iconCode = "OMNY",
      targetValue = 1.0,
    ) {
      it.totalRides
    },
    AchievementDefinition(
      id = "BOROUGH_EXPLORER",
      title = "Four-Borough Straphanger",
      description = "Visit stations across Manhattan, Brooklyn, Queens, and The Bronx",
      iconCode = "NYC4",
      targetValue = 4.0,
    ) {
      it.mainlandBoroughsVisited
    },
    AchievementDefinition(
      id = "EXPRESS_MASTER",
      title = "Express Track Master",
      description = "Ride 3 or more Express subway legs skipping local stops",
      iconCode = "EXP",
      targetValue = 3.0,
    ) {
      it.expressLegs
    },
    AchievementDefinition(
      id = "LINE_COMPLETIONIST",
      title = "End-to-End Line Finisher",
      description = "Complete 100% of the corridor segments on any NYC Subway line",
      iconCode = "100%",
      targetValue = 1.0,
    ) {
      it.completedLines
    },
    AchievementDefinition(
      id = "MILEAGE_EXPLORER",
      title = "25 Unique System Miles",
      description = "Unlock 25+ unique miles of physical NYC Subway track",
      iconCode = "25MI",
      targetValue = 25.0,
    ) {
      it.uniqueCorridorMiles
    },
    AchievementDefinition(
      id = "SHUTTLE_COLLECTOR",
      title = "Shuttle Master",
      description = "Ride the 42 St, Franklin Ave, or Rockaway Park Shuttle (S)",
      iconCode = "S",
      targetValue = 1.0,
    ) {
      it.shuttleRides
    },
    AchievementDefinition(
      id = "RUSH_HOUR_WARRIOR",
      title = "Rush Hour Warrior",
      description = "Complete 5+ rides during weekday Rush Peak hours",
      iconCode = "PEAK",
      targetValue = 5.0,
    ) {
      it.rushPeakRides
    },
    AchievementDefinition(
      id = "LATE_NIGHT_OWL",
      title = "Night Owl Local",
      description = "Take a late-night subway ride between 11:45 PM and 5:30 AM",
      iconCode = "OWL",
      targetValue = 1.0,
    ) {
      it.lateNightRides
    },
  )

object AchievementEngine {
  /** GTFS route IDs of the 42 St, Franklin Av and Rockaway Park shuttles. */
  val SHUTTLE_ROUTE_IDS = setOf("GS", "FS", "H")

  fun evaluateAchievements(
    rides: List<CompleteRideRecord>,
    uniqueCorridorMiles: Double,
    lineStats: List<LineCompletionStat>,
    boroughStats: List<BoroughStat>,
  ): List<SubwayAchievement> {
    val legs = rides.flatMap { it.legs }
    fun ridesDuring(period: ServicePeriod): Double =
      rides.count { rec -> rec.legs.any { it.servicePeriod == period } }.toDouble()
    val progress =
      AchievementProgress(
        totalRides = rides.size.toDouble(),
        mainlandBoroughsVisited =
          boroughStats
            .filter { it.borough != Borough.STATEN_ISLAND }
            .count { it.stationsVisited > 0 }
            .toDouble(),
        expressLegs = legs.count { it.isExpress }.toDouble(),
        completedLines =
          lineStats.count { it.completionPercent >= LINE_COMPLETE_PERCENT }.toDouble(),
        uniqueCorridorMiles = uniqueCorridorMiles,
        shuttleRides = legs.count { it.routeId in SHUTTLE_ROUTE_IDS }.toDouble(),
        rushPeakRides = ridesDuring(ServicePeriod.RUSH_PEAK),
        lateNightRides = ridesDuring(ServicePeriod.LATE_NIGHT),
      )
    return ACHIEVEMENTS.map { it.evaluate(progress) }
  }
}
