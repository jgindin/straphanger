package com.straphanger.app.data.gtfs

/** One station-to-station step of a path. */
internal data class PathStep(
  val fromStationId: String,
  val toStationId: String,
  val segment: SubwaySegment?,
  /** The route ridden on this step; null for a walk between platforms. */
  val routeId: String? = null,
)

private data class AdjacencyEdge(
  val toStationId: String,
  val segment: SubwaySegment?, // null if in-complex transfer walk
  val costMiles: Double,
)

/** Mile-equivalent cost of getting off one train to take another. */
private const val ROUTE_CHANGE_COST_MILES = 1.0

/** Extra mile-equivalent cost of walking between platforms, on top of the route change. */
private const val PLATFORM_WALK_PENALTY_MILES = 0.35

/** Mile-equivalent cost of walking between two platforms of one station complex. */
private const val COMPLEX_WALK_MILES = 0.05

/** Extra mile-equivalent cost per segment ridden on a route other than the preferred one. */
private const val NON_PREFERRED_ROUTE_PENALTY_MILES = 0.25

/**
 * Route-aware shortest path. Riding a segment on a route costs its miles; getting off a train (to
 * change routes at the same station, or to walk to another platform) costs
 * [ROUTE_CHANGE_COST_MILES]; walking between platforms of a complex costs its edge plus
 * [PLATFORM_WALK_PENALTY_MILES]. So a one-seat ride wins unless a transfer saves more than a mile.
 * Trips start and end on foot; the final alighting cost is the same for every path.
 */
internal class RoutePathFinder(
  private val segments: List<SubwaySegment>,
  private val stationsByComplex: Map<String, List<SubwayStation>>,
) {
  private val adjacency: Map<String, List<AdjacencyEdge>> by lazy { buildAdjacency() }

  private fun buildAdjacency(): Map<String, List<AdjacencyEdge>> {
    val map = mutableMapOf<String, MutableList<AdjacencyEdge>>()
    fun add(from: String, edge: AdjacencyEdge) {
      map.getOrPut(from) { mutableListOf() }.add(edge)
    }
    for (seg in segments) {
      add(seg.fromStationId, AdjacencyEdge(seg.toStationId, seg, seg.distanceMiles))
      add(seg.toStationId, AdjacencyEdge(seg.fromStationId, seg, seg.distanceMiles))
    }
    // Connect sibling platforms inside the same station complex with a small transfer penalty
    for ((_, complexStations) in stationsByComplex) {
      for (i in complexStations.indices) {
        for (j in i + 1 until complexStations.size) {
          val a = complexStations[i]
          val b = complexStations[j]
          add(a.id, AdjacencyEdge(b.id, null, COMPLEX_WALK_MILES))
          add(b.id, AdjacencyEdge(a.id, null, COMPLEX_WALK_MILES))
        }
      }
    }
    return map
  }

  /** The cheapest path between two stations, or an empty list when there is none (or no need). */
  fun findShortestPath(
    startStationId: String,
    endStationId: String,
    preferredRouteId: String?,
  ): List<PathStep> =
    if (startStationId == endStationId) {
      emptyList()
    } else {
      PathSearch(adjacency, preferredRouteId, RouteState(startStationId, null))
        .pathTo(RouteState(endStationId, null))
    }
}

/**
 * A search state: at [stationId], either riding [routeId] or on foot ([routeId] == null). On foot
 * is where every trip starts and ends, and where transfers happen.
 */
private data class RouteState(val stationId: String, val routeId: String?)

/** How a state was reached: the previous state and the station-to-station step, if any. */
private data class Arrival(val previous: RouteState, val step: PathStep?)

/** One Dijkstra search from [start]. */
private class PathSearch(
  private val adjacency: Map<String, List<AdjacencyEdge>>,
  private val preferredRouteId: String?,
  private val start: RouteState,
) {
  private val dist = mutableMapOf(start to 0.0)
  private val prev = mutableMapOf<RouteState, Arrival>()
  private val settled = mutableSetOf<RouteState>()
  private val queue = StateQueue()

  fun pathTo(goal: RouteState): List<PathStep> {
    search(goal)
    return if (goal in prev) walkBack(goal) else emptyList()
  }

  private fun search(goal: RouteState) {
    queue.push(0.0, start)
    var reachedGoal = false
    while (!reachedGoal && queue.isNotEmpty()) {
      val (cost, state) = queue.pop()
      if (settled.add(state)) {
        reachedGoal = state == goal
        if (!reachedGoal) expand(state, cost)
      }
    }
  }

  private fun expand(state: RouteState, cost: Double) {
    val edges = adjacency[state.stationId].orEmpty()
    val ridingRoute = state.routeId
    if (ridingRoute == null) expandOnFoot(state, cost, edges)
    else expandRiding(state, ridingRoute, cost, edges)
  }

  private fun expandOnFoot(state: RouteState, cost: Double, edges: List<AdjacencyEdge>) {
    // Board any route serving this station.
    val boardable = edges.mapNotNullTo(mutableSetOf()) { e -> e.segment?.routes }.flatten().toSet()
    for (r in boardable) relax(state, RouteState(state.stationId, r), cost, null)
    // Walk to another platform of the same complex.
    for (e in edges.filter { it.segment == null }) {
      relax(
        state,
        RouteState(e.toStationId, null),
        cost + e.costMiles + PLATFORM_WALK_PENALTY_MILES,
        PathStep(state.stationId, e.toStationId, null, null),
      )
    }
  }

  private fun expandRiding(
    state: RouteState,
    ridingRoute: String,
    cost: Double,
    edges: List<AdjacencyEdge>,
  ) {
    // Stay on the train.
    val routePenalty =
      if (preferredRouteId != null && ridingRoute != preferredRouteId) {
        NON_PREFERRED_ROUTE_PENALTY_MILES
      } else {
        0.0
      }
    for (e in edges) {
      val seg = e.segment
      if (seg != null && ridingRoute in seg.routes) {
        relax(
          state,
          RouteState(e.toStationId, ridingRoute),
          cost + e.costMiles + routePenalty,
          PathStep(state.stationId, e.toStationId, seg, ridingRoute),
        )
      }
    }
    // Get off.
    relax(state, RouteState(state.stationId, null), cost + ROUTE_CHANGE_COST_MILES, null)
  }

  private fun relax(from: RouteState, to: RouteState, cost: Double, step: PathStep?) {
    if (cost < (dist[to] ?: Double.POSITIVE_INFINITY)) {
      dist[to] = cost
      prev[to] = Arrival(from, step)
      queue.push(cost, to)
    }
  }

  private fun walkBack(goal: RouteState): List<PathStep> {
    val path = mutableListOf<PathStep>()
    var curr = goal
    var broken = false
    while (!broken && curr != start) {
      val arrival = prev[curr]
      if (arrival == null) {
        broken = true
      } else {
        arrival.step?.let { path.add(0, it) }
        curr = arrival.previous
      }
    }
    return if (broken) emptyList() else path
  }
}

/** A binary min-heap of search states by cost. */
private class StateQueue {
  private val heap = mutableListOf<Pair<Double, RouteState>>()

  fun isNotEmpty(): Boolean = heap.isNotEmpty()

  fun push(cost: Double, state: RouteState) {
    heap.add(cost to state)
    var i = heap.lastIndex
    while (i > 0 && heap[(i - 1) / 2].first > heap[i].first) {
      val parent = (i - 1) / 2
      swap(i, parent)
      i = parent
    }
  }

  fun pop(): Pair<Double, RouteState> {
    val top = heap.first()
    val last = heap.removeAt(heap.lastIndex)
    if (heap.isNotEmpty()) {
      heap[0] = last
      siftDown()
    }
    return top
  }

  private fun siftDown() {
    var i = 0
    var smallest = smallestOf(i)
    while (smallest != i) {
      swap(i, smallest)
      i = smallest
      smallest = smallestOf(i)
    }
  }

  // The index of the smallest of heap[i] and its children.
  private fun smallestOf(i: Int): Int {
    val l = 2 * i + 1
    val r = l + 1
    var smallest = i
    if (l < heap.size && heap[l].first < heap[smallest].first) smallest = l
    if (r < heap.size && heap[r].first < heap[smallest].first) smallest = r
    return smallest
  }

  private fun swap(a: Int, b: Int) {
    heap[a] = heap[b].also { heap[b] = heap[a] }
  }
}
