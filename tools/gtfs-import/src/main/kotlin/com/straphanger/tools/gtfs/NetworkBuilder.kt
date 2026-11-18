package com.straphanger.tools.gtfs

import java.io.File
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.Json

object RouteMetadataFile {
  fun read(file: File): Map<String, RouteMetadata> = Json.decodeFromString(file.readText())
}

/** A GTFS trip reduced to the parent stations it calls at, in order, without repeats. */
private data class Trip(
  val trip: GtfsTrip,
  val stations: List<String>,
  val departureSeconds: Int,
)

/** An unordered pair of stations. */
private data class PairKey(val a: String, val b: String) {
  companion object {
    fun of(x: String, y: String) = if (x < y) PairKey(x, y) else PairKey(y, x)
  }
}

/** A pair of consecutive stops: the routes that run it, its orientation and the shapes seen. */
private class PairInfo {
  val routes: MutableSet<String> = mutableSetOf()
  var from: String? = null
  var to: String? = null
  val shapeCounts: MutableMap<String, Int> = mutableMapOf()
}

/** The routes calling at each station: by day, late at night, and at any time. */
private class StationRoutes(
  val daytimeAt: Map<String, Set<String>>,
  val lateNightAt: Map<String, Set<String>>,
  val anyAt: Map<String, Set<String>>,
)

private fun Collection<String>.orderedBy(routeOrder: Map<String, Int>): List<String> = sortedBy {
  routeOrder[it] ?: Int.MAX_VALUE
}

private fun Map<String, RouteMetadata>.trunkOf(routeId: String): String =
  getValue(routeId).trunkGroup

private fun Map<String, GtfsStop>.straightMiles(k: PairKey): Double =
  NetworkBuilder.haversineMiles(getValue(k.a), getValue(k.b))

// The most frequent station sequence among [trips] running in [direction], ties broken by text.
private fun mostCommonPattern(trips: List<Trip>, direction: Int): List<String>? =
  trips
    .filter { it.trip.directionId == direction }
    .groupingBy { it.stations }
    .eachCount()
    .entries
    .sortedWith(
      compareByDescending<Map.Entry<List<String>, Int>> { it.value }
        .thenBy { it.key.joinToString(",") },
    )
    .firstOrNull()
    ?.key

private const val FIRST_DAYTIME_HOUR = 6
private const val LAST_DAYTIME_HOUR = 21
private const val FIRST_WEEKEND_HOUR = 10
private const val LAST_WEEKEND_HOUR = 17
private const val LAST_LATE_NIGHT_HOUR = 4
private const val HOURS_PER_DAY = 24
private const val SECONDS_PER_HOUR = 3600

/** Decimal places kept in the output: distances in miles, and coordinates in degrees. */
private const val DISTANCE_DECIMALS = 4
private const val COORDINATE_DECIMALS = 6

/**
 * Turns a GTFS feed plus the MTA station list into the app's [SubwayNetworkData]. The rules are
 * documented in tools/gtfs-import/README.md.
 */
class NetworkBuilder(
  private val feed: GtfsFeed,
  private val stationListings: List<StationListing>,
  private val routeMetadata: Map<String, RouteMetadata>,
) {
  /** Counters printed by Main so a regeneration can be sanity-checked. */
  data class Report(
    var expandedPairs: Int = 0,
    var keptPairs: Int = 0,
    var shapeFallbacks: Int = 0,
    val crossTrunkExpansions: MutableList<String> = mutableListOf(),
  )

  val report = Report()

  private val parents = feed.stops.filter { it.locationType == 1 }.associateBy { it.stopId }
  private val listingById = stationListings.associateBy { it.gtfsStopId }
  private val calendarById = feed.calendars.associateBy { it.serviceId }
  private val gtfsRoutes = feed.routes.sortedBy { it.sortOrder }
  private val routeOrder = gtfsRoutes.mapIndexed { i, r -> r.routeId to i }.toMap()

  fun build(sourceDate: String): SubwayNetworkData {
    val missingListings = parents.keys.filter { it !in listingById }
    require(missingListings.isEmpty()) {
      "Stations missing from the station list: $missingListings"
    }
    val trips = trips()
    val servedRoutes = trips.map { it.trip.routeId }.toSet()
    val missingMeta = servedRoutes.filter { it !in routeMetadata }
    require(missingMeta.isEmpty()) { "route-metadata.json has no entry for routes $missingMeta" }

    val stopsAtByRoute = stopPatterns(trips)
    val stationRoutes = stationRoutes(trips)
    val pairs = stopPairs(trips)
    val expansion = ExpressExpansion(report, routeMetadata) { parents.straightMiles(it) }
    expansion.expandAll(pairs)
    val segments = segments(pairs, expansion)
    return SubwayNetworkData(
      sourceDate = sourceDate,
      routes = routes(servedRoutes, expansion.expandedRoutes, segments, stopsAtByRoute),
      stations = stations(stationRoutes),
      segments = segments,
    )
  }

  private fun trips(): List<Trip> {
    val childToParent =
      feed.stops.filter { it.parentStation != null }.associate { it.stopId to it.parentStation!! }
    val tripById = feed.trips.associateBy { it.tripId }
    return feed.tripStops.mapNotNull { ts ->
      val stations =
        ts.stopIds
          .map { childToParent[it] ?: it }
          .filter { it in parents }
          .fold(mutableListOf<String>()) { acc, s ->
            if (acc.lastOrNull() != s) acc.add(s)
            acc
          }
      tripById[ts.tripId]
        ?.takeIf { stations.size >= 2 }
        ?.let { Trip(it, stations, ts.firstDepartureSeconds) }
    }
  }

  private fun period(t: Trip): ServicePeriod? {
    val cal = calendarById[t.trip.serviceId] ?: return null
    val hour = t.departureSeconds / SECONDS_PER_HOUR
    return when {
      cal.runsWeekdays && hour in FIRST_DAYTIME_HOUR..LAST_DAYTIME_HOUR -> ServicePeriod.DAYTIME
      cal.runsWeekdays && hour % HOURS_PER_DAY in 0..LAST_LATE_NIGHT_HOUR ->
        ServicePeriod.LATE_NIGHT
      cal.runsSaturday && !cal.runsWeekdays && hour in FIRST_WEEKEND_HOUR..LAST_WEEKEND_HOUR ->
        ServicePeriod.WEEKEND
      else -> null
    }
  }

  // Per-route stop patterns: the most common direction-0 pattern of each window, or the most
  // common direction-1 pattern reversed.
  private fun stopPatterns(trips: List<Trip>): Map<String, Map<ServicePeriod, List<String>>> {
    val stopsAtByRoute = mutableMapOf<String, Map<ServicePeriod, List<String>>>()
    for ((routeId, routeTrips) in trips.groupBy { it.trip.routeId }) {
      val byPeriod =
        routeTrips
          .mapNotNull { t -> period(t)?.let { it to t } }
          .groupBy({ it.first }, { it.second })
      val entries = sortedMapOf<ServicePeriod, List<String>>()
      for ((p, inWindow) in byPeriod) {
        val pattern = mostCommonPattern(inWindow, 0) ?: mostCommonPattern(inWindow, 1)?.reversed()
        if (pattern != null) entries[p] = pattern
      }
      stopsAtByRoute[routeId] = entries
    }
    return stopsAtByRoute
  }

  private fun stationRoutes(trips: List<Trip>): StationRoutes {
    val daytimeAt = mutableMapOf<String, MutableSet<String>>()
    val lateNightAt = mutableMapOf<String, MutableSet<String>>()
    val anyAt = mutableMapOf<String, MutableSet<String>>()
    for (t in trips) {
      val atStation =
        when (period(t)) {
          ServicePeriod.DAYTIME -> daytimeAt
          ServicePeriod.LATE_NIGHT -> lateNightAt
          else -> null
        }
      for (s in t.stations) {
        anyAt.getOrPut(s) { mutableSetOf() }.add(t.trip.routeId)
        atStation?.getOrPut(s) { mutableSetOf() }?.add(t.trip.routeId)
      }
    }
    return StationRoutes(daytimeAt, lateNightAt, anyAt)
  }

  // Consecutive stop pairs from every distinct trip pattern.
  private fun stopPairs(trips: List<Trip>): Map<PairKey, PairInfo> {
    val pairs = linkedMapOf<PairKey, PairInfo>()
    // Direction 0 first so segments are oriented the way direction-0 trips run.
    for (t in trips.sortedBy { it.trip.directionId }) {
      for ((x, y) in t.stations.zipWithNext()) {
        val info = pairs.getOrPut(PairKey.of(x, y)) { PairInfo() }
        info.routes.add(t.trip.routeId)
        if (info.from == null) {
          info.from = x
          info.to = y
        }
        val shapeId = t.trip.shapeId
        // Count a shape only for trips running in the segment's orientation.
        if (shapeId != null && shapeId in feed.shapes && info.from == x) {
          info.shapeCounts.merge(shapeId, 1, Int::plus)
        }
      }
    }
    return pairs
  }

  // Segments with shape distances, one per kept pair.
  private fun segments(
    pairs: Map<PairKey, PairInfo>,
    expansion: ExpressExpansion,
  ): List<SubwaySegment> {
    val measurer = ShapeMeasurer(feed.shapes, report)
    return expansion.owners.entries.sortedWith(compareBy({ it.key.a }, { it.key.b })).map {
      (key, byTrunk) ->
      val info = pairs.getValue(key)
      val from = info.from!!
      val to = info.to!!
      val trunk =
        byTrunk.entries
          .sortedWith(
            compareByDescending<Map.Entry<String, Set<String>>> { it.value.size }.thenBy { it.key },
          )
          .first()
          .key
      val shapeId =
        info.shapeCounts.entries
          .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
          .firstOrNull()
          ?.key
      val (miles, line) = measurer.measure(shapeId, parents.getValue(from), parents.getValue(to))
      SubwaySegment(
        segmentId = "$from-$to",
        trunkId = trunk,
        fromStationId = from,
        toStationId = to,
        distanceMiles = round(miles, DISTANCE_DECIMALS),
        routes =
          (byTrunk.values.flatten().toSet() + expansion.credited[key].orEmpty()).orderedBy(
            routeOrder,
          ),
        polyline =
          simplify(line, MIN_POINT_SPACING_METERS).map {
            GeoPoint(round(it.lat, COORDINATE_DECIMALS), round(it.lng, COORDINATE_DECIMALS))
          },
      )
    }
  }

  private fun stations(stationRoutes: StationRoutes): List<SubwayStation> {
    val complexNames =
      parents.keys
        .groupBy { listingById.getValue(it).complexId }
        .mapValues { (_, ids) ->
          ids.sorted().map { listingById.getValue(it).stopName }.distinct().joinToString(" / ")
        }
    val complexSizes = parents.keys.groupingBy { listingById.getValue(it).complexId }.eachCount()
    return parents.values
      .sortedBy { it.stopId }
      .map { stop ->
        val listing = listingById.getValue(stop.stopId)
        val all = stationRoutes.anyAt[stop.stopId].orEmpty()
        SubwayStation(
          id = stop.stopId,
          name = stop.name,
          complexId = listing.complexId,
          complexName = complexNames.getValue(listing.complexId),
          borough = listing.borough,
          lat = round(stop.lat, COORDINATE_DECIMALS),
          lng = round(stop.lng, COORDINATE_DECIMALS),
          daytimeRoutes = (stationRoutes.daytimeAt[stop.stopId] ?: all).orderedBy(routeOrder),
          lateNightRoutes = stationRoutes.lateNightAt[stop.stopId].orEmpty().orderedBy(routeOrder),
          isTransferHub =
            complexSizes.getValue(listing.complexId) > 1 ||
              all.map { routeMetadata.trunkOf(it) }.distinct().size > 1,
        )
      }
  }

  private fun routes(
    servedRoutes: Set<String>,
    expandedRoutes: Set<String>,
    segments: List<SubwaySegment>,
    stopsAtByRoute: Map<String, Map<ServicePeriod, List<String>>>,
  ): List<SubwayRouteInfo> =
    gtfsRoutes
      .filter { it.routeId in servedRoutes }
      .map { r ->
        val meta = routeMetadata.getValue(r.routeId)
        SubwayRouteInfo(
          routeId = r.routeId,
          name = meta.name,
          hexColor = "#" + r.color.uppercase(),
          textHexColor = "#" + r.textColor.uppercase(),
          trunkGroup = meta.trunkGroup,
          division = meta.division,
          isExpressCapable = r.routeId in expandedRoutes,
          totalRouteMiles =
            round(segments.filter { r.routeId in it.routes }.sumOf { it.distanceMiles }, 2),
          stopsAt = stopsAtByRoute[r.routeId].orEmpty(),
        )
      }

  companion object {
    const val MIN_POINT_SPACING_METERS = 50.0
    private const val METERS_PER_MILE = 1609.344

    /**
     * Keeps the first and last points and drops any point less than [minSpacingMeters] along the
     * line from the last kept one.
     */
    fun simplify(points: List<GeoPoint>, minSpacingMeters: Double): List<GeoPoint> {
      if (points.size <= 2) return points
      val kept = mutableListOf(points.first())
      var sinceKept = 0.0
      for (k in 1 until points.lastIndex) {
        sinceKept +=
          haversineMiles(points[k - 1].lat, points[k - 1].lng, points[k].lat, points[k].lng) *
            METERS_PER_MILE
        if (sinceKept >= minSpacingMeters) {
          kept.add(points[k])
          sinceKept = 0.0
        }
      }
      val last = points.last()
      sinceKept +=
        haversineMiles(
          points[points.lastIndex - 1].lat,
          points[points.lastIndex - 1].lng,
          last.lat,
          last.lng,
        ) * METERS_PER_MILE
      // Replace a too-close final interior point with the endpoint so spacing stays >= the minimum.
      if (kept.size > 1 && sinceKept < minSpacingMeters) kept.removeAt(kept.lastIndex)
      kept.add(last)
      return kept
    }

    fun haversineMiles(a: GtfsStop, b: GtfsStop) = haversineMiles(a.lat, a.lng, b.lat, b.lng)

    fun haversineMiles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
      val r = 3958.8
      val dLat = (lat2 - lat1) * PI / 180
      val dLng = (lng2 - lng1) * PI / 180
      val h =
        sin(dLat / 2) * sin(dLat / 2) +
          cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
      return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun round(v: Double, places: Int): Double {
      var f = 1.0
      repeat(places) { f *= 10 }
      return (v * f).roundToLong() / f
    }
  }
}

/** A shape stretch must be 0.95x to 3x the straight-line distance, with a little slack in miles. */
private const val SHAPE_MIN_RATIO = 0.95
private const val SHAPE_MIN_SLACK = 0.01
private const val SHAPE_MAX_RATIO = 3.0
private const val SHAPE_MAX_SLACK = 0.05

/** An express pair can be laid onto local track no longer than this times its straight line. */
private const val EXPANSION_DETOUR_LIMIT = 1.3

/** The routes of one trunk that run a stop pair. */
private data class ExpansionUnit(val key: PairKey, val trunk: String, val routes: Set<String>)

private typealias PairGraph = MutableMap<String, MutableList<Pair<String, Double>>>

/**
 * Express expansion: shortest pairs first, each pair is either kept as track or, when kept pairs of
 * the same trunk (or, failing that, of any trunk) already join its ends closely enough, laid onto
 * them as an express run.
 */
private class ExpressExpansion(
  private val report: NetworkBuilder.Report,
  private val routeMetadata: Map<String, RouteMetadata>,
  private val straight: (PairKey) -> Double,
) {
  private val trunkGraph = mutableMapOf<String, PairGraph>()
  // All kept pairs regardless of trunk; used when the express route's own trunk has no local
  // path.
  private val anyTrunkGraph: PairGraph = mutableMapOf()

  /** Kept pair -> trunk -> routes. */
  val owners = mutableMapOf<PairKey, MutableMap<String, MutableSet<String>>>()

  /** Kept pair -> routes of express pairs laid onto it. */
  val credited = mutableMapOf<PairKey, MutableSet<String>>()

  val expandedRoutes = mutableSetOf<String>()

  fun expandAll(pairs: Map<PairKey, PairInfo>) {
    pairs
      .flatMap { (key, info) ->
        info.routes
          .groupBy { routeMetadata.trunkOf(it) }
          .map { (trunk, rs) -> ExpansionUnit(key, trunk, rs.toSet()) }
      }
      .sortedWith(
        compareBy<ExpansionUnit> { straight(it.key) }
          .thenBy { it.key.a }
          .thenBy { it.key.b }
          .thenBy { it.trunk },
      )
      .forEach(::add)
  }

  private fun add(u: ExpansionUnit) {
    val graph = trunkGraph.getOrPut(u.trunk) { mutableMapOf() }
    val limit = EXPANSION_DETOUR_LIMIT * straight(u.key)
    // A pair another trunk already keeps is shared track: this trunk joins it rather than
    // expanding.
    val sharedWithOtherTrunk = u.key in owners
    val sameTrunk = if (sharedWithOtherTrunk) null else shortestPath(graph, u.key.a, u.key.b, limit)
    val path =
      if (sharedWithOtherTrunk) null
      else sameTrunk ?: shortestPath(anyTrunkGraph, u.key.a, u.key.b, limit)
    if (path != null) {
      layOnto(u, path, crossTrunk = sameTrunk == null)
    } else {
      keep(u, if (sharedWithOtherTrunk) listOf(graph) else listOf(graph, anyTrunkGraph))
    }
  }

  private fun layOnto(u: ExpansionUnit, path: List<String>, crossTrunk: Boolean) {
    report.expandedPairs++
    if (crossTrunk) {
      report.crossTrunkExpansions.add(
        "${u.key.a}-${u.key.b} ${u.routes.sorted()} via ${path.drop(1).dropLast(1)}",
      )
    }
    for ((x, y) in path.zipWithNext()) {
      credited.getOrPut(PairKey.of(x, y)) { mutableSetOf() }.addAll(u.routes)
    }
    expandedRoutes.addAll(u.routes)
  }

  private fun keep(u: ExpansionUnit, graphs: List<PairGraph>) {
    report.keptPairs++
    val w = straight(u.key)
    for (g in graphs) {
      g.getOrPut(u.key.a) { mutableListOf() }.add(u.key.b to w)
      g.getOrPut(u.key.b) { mutableListOf() }.add(u.key.a to w)
    }
    owners.getOrPut(u.key) { mutableMapOf() }.getOrPut(u.trunk) { mutableSetOf() }.addAll(u.routes)
  }
}

/** Shortest path a -> b over kept pairs, or null if none is within [limit] miles. */
private fun shortestPath(
  graph: Map<String, List<Pair<String, Double>>>,
  a: String,
  b: String,
  limit: Double,
): List<String>? =
  if (a in graph && b in graph) BoundedDijkstra(graph, a, limit).pathTo(b) else null

/** A Dijkstra search from [start] that gives up on paths longer than [limit]. */
private class BoundedDijkstra(
  private val graph: Map<String, List<Pair<String, Double>>>,
  private val start: String,
  private val limit: Double,
) {
  private val dist = hashMapOf(start to 0.0)
  private val prev = HashMap<String, String>()
  private val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })

  fun pathTo(goal: String): List<String>? {
    val withinLimit = search(goal)
    return if (withinLimit && goal in dist) walkBack(goal) else null
  }

  // False when the nearest unsettled station is already beyond the limit.
  private fun search(goal: String): Boolean {
    queue.add(start to 0.0)
    var outcome: Boolean? = null
    while (outcome == null && queue.isNotEmpty()) {
      val (node, d) = queue.poll()
      outcome =
        when {
          d > (dist[node] ?: Double.MAX_VALUE) -> null
          d > limit -> false
          node == goal -> true
          else -> {
            relaxFrom(node, d)
            null
          }
        }
    }
    return outcome != false
  }

  private fun relaxFrom(node: String, d: Double) {
    for ((next, w) in graph[node].orEmpty()) {
      val nd = d + w
      if (nd <= limit && nd < (dist[next] ?: Double.MAX_VALUE)) {
        dist[next] = nd
        prev[next] = node
        queue.add(next to nd)
      }
    }
  }

  private fun walkBack(goal: String): List<String> {
    val path = mutableListOf(goal)
    while (path.last() != start) path.add(prev.getValue(path.last()))
    return path.reversed()
  }
}

/** Fallback length for a stop pair without a usable shape: the straight line times this. */
private const val STRAIGHT_LINE_DETOUR = 1.08

/**
 * Measures stop pairs along [shapes]. Each pair that falls back to a straight line is counted in
 * [report].
 */
private class ShapeMeasurer(
  private val shapes: Map<String, List<GeoPoint>>,
  private val report: NetworkBuilder.Report,
) {
  /**
   * Length along the shape between the shape points nearest each stop, and that stretch of the
   * shape. Falls back to haversine x 1.08 and a straight line when the shape is missing, or when
   * the matched stretch is implausible (shorter than the straight line or more than 3x it).
   */
  fun measure(shapeId: String?, from: GtfsStop, to: GtfsStop): Pair<Double, List<GeoPoint>> {
    val straight = NetworkBuilder.haversineMiles(from.lat, from.lng, to.lat, to.lng)
    val shape = shapeId?.let { shapes[it] }?.takeIf { it.size >= 2 }
    val measured = shape?.let { plausibleStretch(it, from, to, straight) }
    if (measured == null) report.shapeFallbacks++
    return measured
      ?: (straight * STRAIGHT_LINE_DETOUR to
        listOf(GeoPoint(from.lat, from.lng), GeoPoint(to.lat, to.lng)))
  }

  private fun plausibleStretch(
    shape: List<GeoPoint>,
    from: GtfsStop,
    to: GtfsStop,
    straight: Double,
  ): Pair<Double, List<GeoPoint>>? {
    val i = nearestIndex(shape, from.lat, from.lng, 0)
    val j = nearestIndex(shape, to.lat, to.lng, i)
    val stretch = shape.subList(i, j + 1)
    val miles =
      stretch.zipWithNext().sumOf { (p, q) ->
        NetworkBuilder.haversineMiles(p.lat, p.lng, q.lat, q.lng)
      }
    val plausible =
      stretch.size >= 2 &&
        miles >= straight * SHAPE_MIN_RATIO - SHAPE_MIN_SLACK &&
        miles <= straight * SHAPE_MAX_RATIO + SHAPE_MAX_SLACK
    return if (plausible) miles to stretch else null
  }

  private fun nearestIndex(shape: List<GeoPoint>, lat: Double, lng: Double, startAt: Int): Int {
    var best = startAt
    var bestD = Double.MAX_VALUE
    for (k in startAt until shape.size) {
      val d = NetworkBuilder.haversineMiles(lat, lng, shape[k].lat, shape[k].lng)
      if (d < bestD) {
        bestD = d
        best = k
      }
    }
    return best
  }
}
