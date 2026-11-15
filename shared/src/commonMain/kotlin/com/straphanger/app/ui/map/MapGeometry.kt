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

/**
 * The map's projection, without clamping: 0 at the box's west edge and 1 at its east edge. The
 * basemap uses it directly, so land beyond the box (eastern Queens, Tottenville) keeps its shape.
 */
internal fun projectLng(lng: Double): Float = ((lng - MIN_LNG) / (MAX_LNG - MIN_LNG)).toFloat()

/** 0 at the box's north edge and 1 at its south edge, without clamping. */
internal fun projectLat(lat: Double): Float =
  (1.0 - (lat - MIN_LAT) / (MAX_LAT - MIN_LAT)).toFloat()

// Stations are clamped just inside the box.
private fun normX(lng: Double): Float = projectLng(lng).coerceIn(MIN_NORM, MAX_NORM)

private fun normY(lat: Double): Float = projectLat(lat).coerceIn(MIN_NORM, MAX_NORM)

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
  val pan: Offset,
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

internal fun MapViewport(width: Float, height: Float, view: MapView): MapViewport =
  MapViewport(width, height, view.zoom, view.pan)

/** How the map is zoomed and where it is panned; [MapViewport] adds the canvas size. */
internal data class MapView(val zoom: Float, val pan: Offset) {
  /**
   * Zooms by [factor], limited to [minZoom]..[maxZoom], keeping the point under [focal] (canvas
   * pixels) where it is. The buttons pass the canvas centre, a pinch passes where it began.
   */
  fun zoomedAbout(factor: Float, focal: Offset, minZoom: Float, maxZoom: Float): MapView {
    val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
    val ratio = newZoom / zoom
    return MapView(newZoom, focal - (focal - pan) * ratio)
  }

  fun pannedBy(delta: Offset): MapView = copy(pan = pan + delta)
}

/** The area the map is first fitted to, in the unit square of [projectGeometry]. */
internal data class MapBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

internal fun PreProjectedMapGeometry.bounds(): MapBounds? =
  if (stations.isEmpty()) null
  else
    MapBounds(
      minX = stations.minOf { it.normX },
      minY = stations.minOf { it.normY },
      maxX = stations.maxOf { it.normX },
      maxY = stations.maxOf { it.normY },
    )

/**
 * The view that shows all of [bounds] in a canvas of [width] by [height], inside [insets] (left,
 * top, right, bottom in pixels) and centred. A tall phone is limited by width, so the west edge of
 * the network sits at the left inset instead of mid-screen.
 */
internal fun fitView(bounds: MapBounds, width: Float, height: Float, insets: Insets): MapView {
  val availableW = (width - insets.left - insets.right).coerceAtLeast(1f)
  val availableH = (height - insets.top - insets.bottom).coerceAtLeast(1f)
  val spanX = (bounds.maxX - bounds.minX).coerceAtLeast(MIN_SPAN)
  val spanY = (bounds.maxY - bounds.minY).coerceAtLeast(MIN_SPAN)
  val zoom = minOf(availableW / (spanX * width), availableH / (spanY * height))
  val centreX = insets.left + availableW / 2
  val centreY = insets.top + availableH / 2
  val midX = (bounds.minX + bounds.maxX) / 2
  val midY = (bounds.minY + bounds.maxY) / 2
  return MapView(zoom, Offset(centreX - midX * width * zoom, centreY - midY * height * zoom))
}

private const val MIN_SPAN = 0.01f

internal data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float)

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
