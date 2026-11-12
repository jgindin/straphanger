package com.straphanger.app.ui.map

import androidx.compose.ui.geometry.Offset
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.gtfs.SubwayStation
import kotlin.math.hypot

internal data class ProjectedStation(
  val station: SubwayStation,
  val normX: Float,
  val normY: Float,
)

internal data class ProjectedSegment(
  val segmentId: String,
  val trunkId: String,
  val routes: List<String>,
  val startX: Float,
  val startY: Float,
  val endX: Float,
  val endY: Float,
)

internal data class PreProjectedMapGeometry(
  val stations: List<ProjectedStation>,
  val segments: List<ProjectedSegment>,
)

// The bounding box of the five boroughs.
private const val MIN_LAT = 40.505
private const val MAX_LAT = 40.900
private const val MIN_LNG = -74.260
private const val MAX_LNG = -73.745
private const val MIN_NORM = 0.02f
private const val MAX_NORM = 0.98f

private fun normX(lng: Double): Float =
  ((lng - MIN_LNG) / (MAX_LNG - MIN_LNG)).toFloat().coerceIn(MIN_NORM, MAX_NORM)

private fun normY(lat: Double): Float =
  (1.0 - (lat - MIN_LAT) / (MAX_LAT - MIN_LAT)).toFloat().coerceIn(MIN_NORM, MAX_NORM)

/**
 * Projects the network's coordinates onto the unit square once, so pan and zoom do not allocate.
 * Segments whose stations are missing are left out.
 */
internal fun projectGeometry(network: SubwayNetworkData): PreProjectedMapGeometry {
  val stById = network.stations.associateBy { it.id }
  val projStations =
    network.stations.map { st ->
      ProjectedStation(station = st, normX = normX(st.lng), normY = normY(st.lat))
    }
  val projSegments =
    network.segments.mapNotNull { seg ->
      val a = stById[seg.fromStationId]
      val b = stById[seg.toStationId]
      if (a == null || b == null) null
      else
        ProjectedSegment(
          segmentId = seg.segmentId,
          trunkId = seg.trunkId,
          routes = seg.routes,
          startX = normX(a.lng),
          startY = normY(a.lat),
          endX = normX(b.lng),
          endY = normY(b.lat),
        )
    }
  return PreProjectedMapGeometry(projStations, projSegments)
}

/** Maps projected coordinates to screen pixels for a canvas of [width] by [height]. */
internal class MapViewport(
  val width: Float,
  val height: Float,
  val zoom: Float,
  private val pan: Offset,
) {
  fun toScreen(normX: Float, normY: Float): Offset =
    Offset(normX * width * zoom + pan.x, normY * height * zoom + pan.y)

  /** True when both ends lie beyond the same edge of the canvas, widened by [margin]. */
  fun isSegmentOffscreen(start: Offset, end: Offset, margin: Float): Boolean =
    beyondEdge(start.x, end.x, width, margin) || beyondEdge(start.y, end.y, height, margin)

  /** True when [point] lies outside the canvas widened by [margin]. */
  fun isPointOffscreen(point: Offset, margin: Float): Boolean =
    point.x !in -margin..(width + margin) || point.y !in -margin..(height + margin)

  private fun beyondEdge(a: Float, b: Float, extent: Float, margin: Float): Boolean =
    maxOf(a, b) < -margin || minOf(a, b) > extent + margin
}

/** The station nearest [tap] on screen, with its distance in pixels; null when there are none. */
internal fun PreProjectedMapGeometry.nearestStation(
  viewport: MapViewport,
  tap: Offset,
): Pair<ProjectedStation, Float>? =
  stations
    .map { ps ->
      val screen = viewport.toScreen(ps.normX, ps.normY)
      ps to hypot(tap.x - screen.x, tap.y - screen.y)
    }
    .minByOrNull { it.second }
