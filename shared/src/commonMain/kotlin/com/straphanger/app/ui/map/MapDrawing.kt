package com.straphanger.app.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.MapColorMode
import com.straphanger.app.ui.theme.MtaColors

private const val GRID_STEP_PX = 120f
private const val GRID_LINE_ARGB = 0xFF121722
private const val SEGMENT_CULL_MARGIN_PX = 50f
private const val STATION_CULL_MARGIN_PX = 20f
private const val UNRIDDEN_TRACK_WIDTH = 4.5f
private const val RIDDEN_GLOW_WIDTH = 13f
private const val RIDDEN_TRACK_WIDTH = 6.5f
private const val RIDDEN_GLOW_ALPHA = 0.28f
private const val HIGHWAY_WIDTH = 1.8f
private const val ARTERIAL_WIDTH = 1.1f
private val TRACK_ZOOM_SCALE = 0.9f..1.8f
private val STATION_ZOOM_SCALE = 0.85f..1.45f

/** Below this zoom only visited stations and transfer hubs are drawn. */
private const val STATION_DETAIL_ZOOM = 1.25f
private const val SELECTED_RADIUS = 9.5f
private const val BOARDED_OR_EXITED_RADIUS = 6.5f
private const val VISITED_RADIUS = 5.2f
private const val TRANSFER_HUB_RADIUS = 4.2f
private const val STATION_RADIUS = 3.0f
private const val STATION_HALO_PX = 2.5f

/** What the map shows: the ride statistics, the route filter and the selected station. */
internal class MapLayers(
  val stats: SubwayStatsSnapshot,
  val colorMode: MapColorMode,
  val selectedRoute: String?,
  val selectedStationId: String?,
)

/**
 * Draws the map: the basemap when there is one (otherwise a grid), then unridden track, ridden
 * track and station dots.
 */
internal fun DrawScope.drawSubwayMap(
  geometry: PreProjectedMapGeometry,
  viewport: MapViewport,
  layers: MapLayers,
  basemap: BasemapPaths?,
) {
  if (basemap != null) drawBasemap(basemap, viewport) else drawGrid(viewport.pan)
  val segments =
    geometry.segments.filter { layers.selectedRoute == null || layers.selectedRoute in it.routes }
  segments.forEach { drawUnriddenSegment(it, viewport) }
  segments.forEach { drawRiddenSegment(it, viewport, layers) }
  geometry.stations
    .filter { layers.selectedRoute == null || layers.selectedRoute in it.station.daytimeRoutes }
    .forEach { drawStation(it, viewport, layers) }
}

/** Water, then land, parks and roads; fills scale with the canvas, road widths are pixels. */
private fun DrawScope.drawBasemap(basemap: BasemapPaths, viewport: MapViewport) {
  drawRect(MtaColors.BasemapWater)
  withTransform(
    {
      translate(viewport.pan.x, viewport.pan.y)
      scale(viewport.width * viewport.zoom, viewport.height * viewport.zoom, pivot = Offset.Zero)
    },
  ) {
    drawPath(basemap.land, MtaColors.BasemapLand)
    drawPath(basemap.parks, MtaColors.BasemapPark)
  }
  val scale = viewport.zoom.coerceIn(TRACK_ZOOM_SCALE)
  drawRoads(basemap, basemap.arterials, viewport, MtaColors.BasemapArterial, ARTERIAL_WIDTH * scale)
  drawRoads(basemap, basemap.highways, viewport, MtaColors.BasemapHighway, HIGHWAY_WIDTH * scale)
}

private fun DrawScope.drawRoads(
  basemap: BasemapPaths,
  roads: Path,
  viewport: MapViewport,
  color: Color,
  width: Float,
) {
  drawPath(
    basemap.roadsOnScreen(roads, viewport),
    color,
    style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
}

/** Subtle dark geographic grid lines that move with the pan. */
private fun DrawScope.drawGrid(pan: Offset) {
  val gridColor = Color(GRID_LINE_ARGB)
  var gx = (pan.x % GRID_STEP_PX)
  while (gx < size.width) {
    drawLine(gridColor, start = Offset(gx, 0f), end = Offset(gx, size.height), strokeWidth = 1f)
    gx += GRID_STEP_PX
  }
  var gy = (pan.y % GRID_STEP_PX)
  while (gy < size.height) {
    drawLine(gridColor, start = Offset(0f, gy), end = Offset(size.width, gy), strokeWidth = 1f)
    gy += GRID_STEP_PX
  }
}

/** Every segment in muted slate grey, whether ridden or not. */
private fun DrawScope.drawUnriddenSegment(seg: ProjectedSegment, viewport: MapViewport) {
  val start = viewport.toScreen(seg.startX, seg.startY)
  val end = viewport.toScreen(seg.endX, seg.endY)
  if (viewport.isSegmentOffscreen(start, end, SEGMENT_CULL_MARGIN_PX)) return
  drawLine(
    color = MtaColors.UnriddenTrack,
    start = start,
    end = end,
    strokeWidth = UNRIDDEN_TRACK_WIDTH * viewport.zoom.coerceIn(TRACK_ZOOM_SCALE),
    cap = StrokeCap.Round,
  )
}

/** A ridden segment in its line colour or heatmap colour, with a glow. */
private fun DrawScope.drawRiddenSegment(
  seg: ProjectedSegment,
  viewport: MapViewport,
  layers: MapLayers,
) {
  val rideCount = layers.stats.segmentRideCounts[seg.segmentId] ?: 0
  val ridden =
    if (layers.selectedRoute != null) {
      layers.stats.segmentRoutesRidden[seg.segmentId]?.contains(layers.selectedRoute) == true
    } else {
      rideCount > 0
    }
  if (!ridden) return
  val start = viewport.toScreen(seg.startX, seg.startY)
  val end = viewport.toScreen(seg.endX, seg.endY)
  val color = riddenSegmentColor(seg, rideCount, layers)
  val scale = viewport.zoom.coerceIn(TRACK_ZOOM_SCALE)
  drawLine(
    color = color.copy(alpha = RIDDEN_GLOW_ALPHA),
    start = start,
    end = end,
    strokeWidth = RIDDEN_GLOW_WIDTH * scale,
    cap = StrokeCap.Round,
  )
  drawLine(
    color = color,
    start = start,
    end = end,
    strokeWidth = RIDDEN_TRACK_WIDTH * scale,
    cap = StrokeCap.Round,
  )
}

private fun riddenSegmentColor(seg: ProjectedSegment, rideCount: Int, layers: MapLayers): Color =
  when (layers.colorMode) {
    MapColorMode.OFFICIAL_MTA_COLORS ->
      MtaColors.routeColor(
        layers.selectedRoute
          ?: layers.stats.segmentDominantRoutes[seg.segmentId]
          ?: seg.routes.firstOrNull().orEmpty(),
      )
    MapColorMode.FREQUENCY_HEATMAP -> MtaColors.heatmapColorForCount(rideCount)
  }

/** A station dot sized and coloured by how it was visited; at low zoom only notable ones. */
private fun DrawScope.drawStation(ps: ProjectedStation, viewport: MapViewport, layers: MapLayers) {
  val st = ps.station
  val center = viewport.toScreen(ps.normX, ps.normY)
  val visit = StationVisit.of(st, layers)
  val hidden =
    viewport.isPointOffscreen(center, STATION_CULL_MARGIN_PX) ||
      (viewport.zoom < STATION_DETAIL_ZOOM && !visit.isVisited && !st.isTransferHub)
  if (hidden) return
  val radius = visit.radius(st) * viewport.zoom.coerceIn(STATION_ZOOM_SCALE)
  drawCircle(color = MtaColors.DarkBg, radius = radius + STATION_HALO_PX, center = center)
  drawCircle(color = visit.fillColor(st), radius = radius, center = center)
}

private data class StationVisit(
  val isVisited: Boolean,
  val isBoardedOrExited: Boolean,
  val isSelected: Boolean,
) {
  fun radius(st: SubwayStation): Float =
    when {
      isSelected -> SELECTED_RADIUS
      isBoardedOrExited -> BOARDED_OR_EXITED_RADIUS
      isVisited -> VISITED_RADIUS
      st.isTransferHub -> TRANSFER_HUB_RADIUS
      else -> STATION_RADIUS
    }

  fun fillColor(st: SubwayStation): Color =
    when {
      isSelected -> MtaColors.AccentGold
      isBoardedOrExited -> Color.White
      isVisited -> MtaColors.routeColor(st.daytimeRoutes.firstOrNull().orEmpty())
      else -> MtaColors.UnriddenStation
    }

  companion object {
    fun of(st: SubwayStation, layers: MapLayers) =
      StationVisit(
        isVisited = st.id in layers.stats.visitedStationIdsStoppedAt,
        isBoardedOrExited = st.id in layers.stats.visitedStationIdsBoardedExited,
        isSelected = st.id == layers.selectedStationId,
      )
  }
}
