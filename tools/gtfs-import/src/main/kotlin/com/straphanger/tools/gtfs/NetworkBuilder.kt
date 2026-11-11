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

/**
 * Turns a GTFS feed plus the MTA station list into the app's [SubwayNetworkData].
 * The rules are documented in tools/gtfs-import/README.md.
 */
class NetworkBuilder(
    private val feed: GtfsFeed,
    private val stationListings: List<StationListing>,
    private val routeMetadata: Map<String, RouteMetadata>
) {
    /** Counters printed by Main so a regeneration can be sanity-checked. */
    data class Report(
        var expandedPairs: Int = 0,
        var keptPairs: Int = 0,
        var shapeFallbacks: Int = 0,
        val crossTrunkExpansions: MutableList<String> = mutableListOf()
    )

    val report = Report()

    private data class Trip(
        val trip: GtfsTrip,
        val stations: List<String>,
        val departureSeconds: Int
    )

    private data class PairKey(val a: String, val b: String) {
        companion object {
            fun of(x: String, y: String) = if (x < y) PairKey(x, y) else PairKey(y, x)
        }
    }

    fun build(sourceDate: String): SubwayNetworkData {
        val parents = feed.stops.filter { it.locationType == 1 }.associateBy { it.stopId }
        val childToParent = feed.stops.filter { it.parentStation != null }.associate { it.stopId to it.parentStation!! }
        val listingById = stationListings.associateBy { it.gtfsStopId }
        val calendarById = feed.calendars.associateBy { it.serviceId }
        val tripById = feed.trips.associateBy { it.tripId }

        val missingListings = parents.keys.filter { it !in listingById }
        require(missingListings.isEmpty()) { "Stations missing from the station list: $missingListings" }

        val gtfsRoutes = feed.routes.sortedBy { it.sortOrder }
        val routeOrder = gtfsRoutes.mapIndexed { i, r -> r.routeId to i }.toMap()

        val trips = feed.tripStops.mapNotNull { ts ->
            val trip = tripById[ts.tripId] ?: return@mapNotNull null
            val stations = ts.stopIds.map { childToParent[it] ?: it }
                .filter { it in parents }
                .fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc.add(s); acc }
            if (stations.size < 2) null else Trip(trip, stations, ts.firstDepartureSeconds)
        }
        val servedRoutes = trips.map { it.trip.routeId }.toSet()
        val missingMeta = servedRoutes.filter { it !in routeMetadata }
        require(missingMeta.isEmpty()) { "route-metadata.json has no entry for routes $missingMeta" }

        fun period(t: Trip): ServicePeriod? {
            val cal = calendarById[t.trip.serviceId] ?: return null
            val hour = t.departureSeconds / 3600
            return when {
                cal.runsWeekdays && hour in 6..21 -> ServicePeriod.DAYTIME
                cal.runsWeekdays && hour % 24 in 0..4 -> ServicePeriod.LATE_NIGHT
                cal.runsSaturday && !cal.runsWeekdays && hour in 10..17 -> ServicePeriod.WEEKEND
                else -> null
            }
        }

        // --- Per-route stop patterns ------------------------------------------------------------
        val stopsAtByRoute = mutableMapOf<String, Map<ServicePeriod, List<String>>>()
        for ((routeId, routeTrips) in trips.groupBy { it.trip.routeId }) {
            val byPeriod = routeTrips.mapNotNull { t -> period(t)?.let { it to t } }
                .groupBy({ it.first }, { it.second })
            val entries = sortedMapOf<ServicePeriod, List<String>>()
            for ((p, inWindow) in byPeriod) {
                fun mostCommon(direction: Int): List<String>? = inWindow
                    .filter { it.trip.directionId == direction }
                    .groupingBy { it.stations }
                    .eachCount()
                    .entries
                    .sortedWith(compareByDescending<Map.Entry<List<String>, Int>> { it.value }.thenBy { it.key.joinToString(",") })
                    .firstOrNull()?.key
                val pattern = mostCommon(0) ?: mostCommon(1)?.reversed()
                if (pattern != null) entries[p] = pattern
            }
            stopsAtByRoute[routeId] = entries
        }

        // --- Routes serving each station per window --------------------------------------------
        val daytimeAt = mutableMapOf<String, MutableSet<String>>()
        val lateNightAt = mutableMapOf<String, MutableSet<String>>()
        val anyAt = mutableMapOf<String, MutableSet<String>>()
        for (t in trips) {
            val p = period(t)
            for (s in t.stations) {
                anyAt.getOrPut(s) { mutableSetOf() }.add(t.trip.routeId)
                if (p == ServicePeriod.DAYTIME) daytimeAt.getOrPut(s) { mutableSetOf() }.add(t.trip.routeId)
                if (p == ServicePeriod.LATE_NIGHT) lateNightAt.getOrPut(s) { mutableSetOf() }.add(t.trip.routeId)
            }
        }
        fun ordered(ids: Collection<String>) = ids.sortedBy { routeOrder[it] ?: Int.MAX_VALUE }

        // --- Consecutive stop pairs from every distinct trip pattern ----------------------------
        data class PairInfo(
            val routes: MutableSet<String> = mutableSetOf(),
            var from: String? = null,
            var to: String? = null,
            val shapeCounts: MutableMap<String, Int> = mutableMapOf()
        )
        val pairs = linkedMapOf<PairKey, PairInfo>()
        // Direction 0 first so segments are oriented the way direction-0 trips run.
        for (t in trips.sortedBy { it.trip.directionId }) {
            for ((x, y) in t.stations.zipWithNext()) {
                val info = pairs.getOrPut(PairKey.of(x, y)) { PairInfo() }
                info.routes.add(t.trip.routeId)
                if (info.from == null) { info.from = x; info.to = y }
                val shapeId = t.trip.shapeId
                if (shapeId != null && shapeId in feed.shapes) {
                    // Count a shape only for trips running in the segment's orientation.
                    if (info.from == x) info.shapeCounts.merge(shapeId, 1, Int::plus)
                }
            }
        }

        // --- Express expansion --------------------------------------------------------------------
        fun trunkOf(routeId: String) = routeMetadata.getValue(routeId).trunkGroup
        fun straight(k: PairKey) = haversineMiles(parents.getValue(k.a), parents.getValue(k.b))

        data class Unit(val key: PairKey, val trunk: String, val routes: Set<String>)
        val units = pairs.flatMap { (key, info) ->
            info.routes.groupBy { trunkOf(it) }.map { (trunk, rs) -> Unit(key, trunk, rs.toSet()) }
        }.sortedWith(compareBy<Unit> { straight(it.key) }.thenBy { it.key.a }.thenBy { it.key.b }.thenBy { it.trunk })

        val trunkGraph = mutableMapOf<String, MutableMap<String, MutableList<Pair<String, Double>>>>()
        // All kept pairs regardless of trunk; used when the express route's own trunk has no local path.
        val anyTrunkGraph = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        val owners = mutableMapOf<PairKey, MutableMap<String, MutableSet<String>>>() // kept pair -> trunk -> routes
        val credited = mutableMapOf<PairKey, MutableSet<String>>() // kept pair -> routes of express pairs laid onto it
        val expandedRoutes = mutableSetOf<String>()

        for (u in units) {
            val graph = trunkGraph.getOrPut(u.trunk) { mutableMapOf() }
            val limit = 1.3 * straight(u.key)
            // A pair another trunk already keeps is shared track: this trunk joins it rather than expanding.
            val sharedWithOtherTrunk = u.key in owners
            val sameTrunk = if (sharedWithOtherTrunk) null else shortestPath(graph, u.key.a, u.key.b, limit)
            val path = if (sharedWithOtherTrunk) null else sameTrunk ?: shortestPath(anyTrunkGraph, u.key.a, u.key.b, limit)
            if (path != null) {
                report.expandedPairs++
                if (sameTrunk == null) report.crossTrunkExpansions.add("${u.key.a}-${u.key.b} ${u.routes.sorted()} via ${path.drop(1).dropLast(1)}")
                for ((x, y) in path.zipWithNext()) {
                    credited.getOrPut(PairKey.of(x, y)) { mutableSetOf() }.addAll(u.routes)
                }
                expandedRoutes.addAll(u.routes)
            } else {
                report.keptPairs++
                val w = straight(u.key)
                for (g in if (sharedWithOtherTrunk) listOf(graph) else listOf(graph, anyTrunkGraph)) {
                    g.getOrPut(u.key.a) { mutableListOf() }.add(u.key.b to w)
                    g.getOrPut(u.key.b) { mutableListOf() }.add(u.key.a to w)
                }
                owners.getOrPut(u.key) { mutableMapOf() }.getOrPut(u.trunk) { mutableSetOf() }.addAll(u.routes)
            }
        }

        // --- Segments with shape distances --------------------------------------------------------
        val segments = owners.entries
            .sortedWith(compareBy({ it.key.a }, { it.key.b }))
            .map { (key, byTrunk) ->
                val info = pairs.getValue(key)
                val from = info.from!!
                val to = info.to!!
                val trunk = byTrunk.entries
                    .sortedWith(compareByDescending<Map.Entry<String, MutableSet<String>>> { it.value.size }.thenBy { it.key })
                    .first().key
                val routes = ordered(byTrunk.values.flatten().toSet() + credited[key].orEmpty())
                val shapeId = info.shapeCounts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .firstOrNull()?.key
                val (miles, line) = measure(shapeId, parents.getValue(from), parents.getValue(to))
                SubwaySegment(
                    segmentId = "$from-$to",
                    trunkId = trunk,
                    fromStationId = from,
                    toStationId = to,
                    distanceMiles = round(miles, 4),
                    routes = routes,
                    polyline = simplify(line, MIN_POINT_SPACING_METERS).map { GeoPoint(round(it.lat, 6), round(it.lng, 6)) }
                )
            }

        // --- Stations -------------------------------------------------------------------------------
        val complexNames = parents.keys.groupBy { listingById.getValue(it).complexId }
            .mapValues { (_, ids) -> ids.sorted().map { listingById.getValue(it).stopName }.distinct().joinToString(" / ") }
        val complexSizes = parents.keys.groupingBy { listingById.getValue(it).complexId }.eachCount()

        val stations = parents.values.sortedBy { it.stopId }.map { stop ->
            val listing = listingById.getValue(stop.stopId)
            val all = anyAt[stop.stopId].orEmpty()
            SubwayStation(
                id = stop.stopId,
                name = stop.name,
                complexId = listing.complexId,
                complexName = complexNames.getValue(listing.complexId),
                borough = listing.borough,
                lat = round(stop.lat, 6),
                lng = round(stop.lng, 6),
                daytimeRoutes = ordered(daytimeAt[stop.stopId] ?: all),
                lateNightRoutes = ordered(lateNightAt[stop.stopId].orEmpty()),
                isTransferHub = complexSizes.getValue(listing.complexId) > 1 ||
                    all.map { trunkOf(it) }.distinct().size > 1
            )
        }

        // --- Routes -----------------------------------------------------------------------------------
        val routes = gtfsRoutes.filter { it.routeId in servedRoutes }.map { r ->
            val meta = routeMetadata.getValue(r.routeId)
            SubwayRouteInfo(
                routeId = r.routeId,
                name = meta.name,
                hexColor = "#" + r.color.uppercase(),
                textHexColor = "#" + r.textColor.uppercase(),
                trunkGroup = meta.trunkGroup,
                division = meta.division,
                isExpressCapable = r.routeId in expandedRoutes,
                totalRouteMiles = round(segments.filter { r.routeId in it.routes }.sumOf { it.distanceMiles }, 2),
                stopsAt = stopsAtByRoute[r.routeId].orEmpty()
            )
        }

        return SubwayNetworkData(sourceDate = sourceDate, routes = routes, stations = stations, segments = segments)
    }

    /** Shortest path a -> b over kept pairs, or null if none is within [limit] miles. */
    private fun shortestPath(
        graph: Map<String, List<Pair<String, Double>>>,
        a: String,
        b: String,
        limit: Double
    ): List<String>? {
        if (a !in graph || b !in graph) return null
        val dist = HashMap<String, Double>()
        val prev = HashMap<String, String>()
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        dist[a] = 0.0
        queue.add(a to 0.0)
        while (queue.isNotEmpty()) {
            val (node, d) = queue.poll()
            if (d > (dist[node] ?: Double.MAX_VALUE)) continue
            if (d > limit) return null
            if (node == b) break
            for ((next, w) in graph[node].orEmpty()) {
                val nd = d + w
                if (nd <= limit && nd < (dist[next] ?: Double.MAX_VALUE)) {
                    dist[next] = nd
                    prev[next] = node
                    queue.add(next to nd)
                }
            }
        }
        if (b !in dist) return null
        val path = mutableListOf(b)
        while (path.last() != a) path.add(prev.getValue(path.last()))
        return path.reversed()
    }

    /**
     * Length along the shape between the shape points nearest each stop, and that stretch of the
     * shape. Falls back to haversine x 1.08 and a straight line when the shape is missing, or when
     * the matched stretch is implausible (shorter than the straight line or more than 3x it).
     */
    private fun measure(shapeId: String?, from: GtfsStop, to: GtfsStop): Pair<Double, List<GeoPoint>> {
        val straight = haversineMiles(from.lat, from.lng, to.lat, to.lng)
        val fallback = straight * 1.08 to listOf(GeoPoint(from.lat, from.lng), GeoPoint(to.lat, to.lng))
        val shape = shapeId?.let { feed.shapes[it] }
        if (shape == null || shape.size < 2) {
            report.shapeFallbacks++
            return fallback
        }
        val i = nearestIndex(shape, from.lat, from.lng, 0)
        val j = nearestIndex(shape, to.lat, to.lng, i)
        val stretch = shape.subList(i, j + 1)
        val miles = stretch.zipWithNext().sumOf { (p, q) -> haversineMiles(p.lat, p.lng, q.lat, q.lng) }
        if (stretch.size < 2 || miles < straight * 0.95 - 0.01 || miles > straight * 3.0 + 0.05) {
            report.shapeFallbacks++
            return fallback
        }
        return miles to stretch
    }

    private fun nearestIndex(shape: List<GeoPoint>, lat: Double, lng: Double, startAt: Int): Int {
        var best = startAt
        var bestD = Double.MAX_VALUE
        for (k in startAt until shape.size) {
            val d = haversineMiles(lat, lng, shape[k].lat, shape[k].lng)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    companion object {
        const val MIN_POINT_SPACING_METERS = 50.0
        private const val METERS_PER_MILE = 1609.344

        /** Keeps the first and last points and drops any point less than [minSpacingMeters] along the line from the last kept one. */
        fun simplify(points: List<GeoPoint>, minSpacingMeters: Double): List<GeoPoint> {
            if (points.size <= 2) return points
            val kept = mutableListOf(points.first())
            var sinceKept = 0.0
            for (k in 1 until points.lastIndex) {
                sinceKept += haversineMiles(points[k - 1].lat, points[k - 1].lng, points[k].lat, points[k].lng) * METERS_PER_MILE
                if (sinceKept >= minSpacingMeters) {
                    kept.add(points[k])
                    sinceKept = 0.0
                }
            }
            val last = points.last()
            sinceKept += haversineMiles(points[points.lastIndex - 1].lat, points[points.lastIndex - 1].lng, last.lat, last.lng) * METERS_PER_MILE
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
            val h = sin(dLat / 2) * sin(dLat / 2) +
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
