package com.straphanger.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.MapColorMode
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 5.0f
private const val ZOOM_STEP = 1.25f
private const val TAP_RADIUS_PX = 48f
private const val FIT_EDGE_DP = 8f
private const val FIT_TOP_DP = 112f

/** The fitted opening view, once the canvas has a size. */
@Composable
private fun rememberOpeningView(geometry: PreProjectedMapGeometry, canvasSize: IntSize): MapView? {
  val density = LocalDensity.current.density
  return remember(geometry, canvasSize, density) {
    if (canvasSize.width == 0) null
    else initialMapView(geometry, canvasSize.width.toFloat(), canvasSize.height.toFloat(), density)
  }
}

/**
 * The view the map opens with: all stations fitted to a canvas of [width] by [height] pixels at
 * screen density [density], with the controls along the top left clear.
 */
internal fun initialMapView(
  geometry: PreProjectedMapGeometry,
  width: Float,
  height: Float,
  density: Float,
): MapView? {
  val bounds = geometry.bounds() ?: return null
  val edge = FIT_EDGE_DP * density
  return fitView(bounds, width, height, Insets(edge, FIT_TOP_DP * density, edge, edge))
}

/** The route filter bar's lines, in the MTA's trunk order. */
private val FILTER_ROUTES =
  listOf(
    "1",
    "2",
    "3",
    "4",
    "5",
    "6",
    "7",
    "A",
    "C",
    "E",
    "B",
    "D",
    "F",
    "M",
    "G",
    "J",
    "Z",
    "L",
    "N",
    "Q",
    "R",
    "W",
    "GS",
    "FS",
    "H",
    "SI",
  )

@Composable
fun SubwayMapScreen(
  uiState: StraphangerUiState,
  network: SubwayNetworkData,
  onColorModeChange: (MapColorMode) -> Unit,
  onRouteFilterSelect: (String?) -> Unit,
  onStationSelect: (String?) -> Unit,
  onStartRideFromStation: (String) -> Unit,
  onEndRideAtStation: (String) -> Unit,
) {
  val projectedGeometry = remember(network) { projectGeometry(network) }
  val basemapPaths = remember(uiState.basemap) { uiState.basemap?.let(::BasemapPaths) }
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var gestureView by remember { mutableStateOf<MapView?>(null) }
  // Until the user moves the map it is fitted to the network; the controls at the top are left
  // clear.
  val fitted = rememberOpeningView(projectedGeometry, canvasSize)
  val view = gestureView ?: fitted ?: MapView(1f, Offset.Zero)
  val selectedStation =
    remember(uiState.selectedStationId, network) {
      network.stations.firstOrNull { it.id == uiState.selectedStationId }
    }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(MtaColors.DarkBg)
        .onSizeChanged { canvasSize = it }
        .testTag("subway_map_screen"),
  ) {
    SubwayMapCanvas(
      geometry = projectedGeometry,
      layers =
        MapLayers(
          stats = uiState.stats,
          colorMode = uiState.mapColorMode,
          selectedRoute = uiState.selectedRouteFilter,
          selectedStationId = uiState.selectedStationId,
        ),
      basemap = basemapPaths,
      view = view,
      onTransform = { centroid, pan, gestureZoom ->
        gestureView = view.zoomedAbout(gestureZoom, centroid, MIN_ZOOM, MAX_ZOOM).pannedBy(pan)
      },
      onStationSelect = onStationSelect,
    )
    MapControlsOverlay(uiState, onColorModeChange, onRouteFilterSelect)
    val centre = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    MapSideControls(
      onZoomIn = { gestureView = view.zoomedAbout(ZOOM_STEP, centre, MIN_ZOOM, MAX_ZOOM) },
      onZoomOut = { gestureView = view.zoomedAbout(1 / ZOOM_STEP, centre, MIN_ZOOM, MAX_ZOOM) },
    )
    if (selectedStation != null) {
      StationDetailSheet(
        station = selectedStation,
        stats = uiState.stats,
        onStartRideFromStation = onStartRideFromStation,
        onEndRideAtStation = onEndRideAtStation,
      )
    }
  }
}

@Composable
private fun SubwayMapCanvas(
  geometry: PreProjectedMapGeometry,
  layers: MapLayers,
  basemap: BasemapPaths?,
  view: MapView,
  onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
  onStationSelect: (String?) -> Unit,
) {
  // The gesture detector outlives recompositions, so it must call the latest callback; the first
  // one holds the view the map opened with and would snap the map back on every gesture.
  val latestOnTransform by rememberUpdatedState(onTransform)
  Canvas(
    modifier =
      // Clipped: the basemap's fills are drawn with the canvas transformed and would otherwise
      // spill over the app header.
      Modifier.fillMaxSize()
        .clipToBounds()
        .testTag("subway_map_canvas")
        .pointerInput(Unit) {
          detectTransformGestures { centroid, pan, gestureZoom, _ ->
            latestOnTransform(centroid, pan, gestureZoom)
          }
        }
        .pointerInput(view) {
          detectTapGestures { tapPos ->
            val viewport = MapViewport(size.width.toFloat(), size.height.toFloat(), view)
            geometry.nearestStation(viewport, tapPos)?.let { (nearest, distance) ->
              onStationSelect(nearest.station.id.takeIf { distance <= TAP_RADIUS_PX })
            }
          }
        },
  ) {
    drawSubwayMap(geometry, MapViewport(size.width, size.height, view), layers, basemap)
  }
}

/** The colour mode toggle and the route bullet filter bar. */
@Composable
private fun BoxScope.MapControlsOverlay(
  uiState: StraphangerUiState,
  onColorModeChange: (MapColorMode) -> Unit,
  onRouteFilterSelect: (String?) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter)) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MtaColors.CardBg.copy(alpha = 0.94f))
          .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(12.dp))
          .padding(8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MapColorMode.entries.forEach { mode ->
          ColorModeChip(mode, active = uiState.mapColorMode == mode, onColorModeChange)
        }
      }
      Text(
        text =
          "${uiState.stats.uniqueCorridorMilesRidden} mi " +
            "(${uiState.stats.systemCompletionPercent}%)",
        color = MtaColors.AccentGold,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("map_header_unique_miles"),
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    RouteFilterBar(uiState.selectedRouteFilter, onRouteFilterSelect)
  }
}

@Composable
private fun ColorModeChip(
  mode: MapColorMode,
  active: Boolean,
  onColorModeChange: (MapColorMode) -> Unit,
) {
  Box(
    modifier =
      Modifier.clip(RoundedCornerShape(8.dp))
        .background(if (active) Color(0xFF0039A6) else Color(0xFF1D2534))
        .clickable { onColorModeChange(mode) }
        .padding(horizontal = 10.dp, vertical = 6.dp)
        .testTag("map_mode_${mode.name}"),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = mode.label,
      color = if (active) Color.White else MtaColors.TextSecondary,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun RouteFilterBar(selectedRoute: String?, onRouteFilterSelect: (String?) -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MtaColors.CardBg.copy(alpha = 0.92f))
        .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(12.dp))
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 10.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.clip(RoundedCornerShape(8.dp))
          .background(if (selectedRoute == null) Color.White else Color(0xFF1E2636))
          .clickable { onRouteFilterSelect(null) }
          .padding(horizontal = 10.dp, vertical = 5.dp)
          .testTag("filter_route_ALL"),
    ) {
      Text(
        text = "All Lines",
        color = if (selectedRoute == null) Color.Black else MtaColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
      )
    }
    FILTER_ROUTES.forEach { rId ->
      val isSelected = selectedRoute == rId
      Box(
        modifier =
          Modifier.clip(RoundedCornerShape(50))
            .border(
              width = if (isSelected) 2.dp else 0.dp,
              color = if (isSelected) Color.White else Color.Transparent,
              shape = RoundedCornerShape(50),
            )
            .clickable { onRouteFilterSelect(rId) }
            .testTag("filter_route_$rId"),
      ) {
        MtaRouteBullet(routeId = rId, size = 26.dp, fontSize = 12.sp)
      }
    }
  }
}

/** The + and − buttons. */
@Composable
private fun BoxScope.MapSideControls(
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
) {
  Column(
    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = Alignment.End,
  ) {
    ZoomButton(text = "+", onClick = onZoomIn, testTag = "map_zoom_in")
    ZoomButton(text = "−", onClick = onZoomOut, testTag = "map_zoom_out")
  }
}

@Composable
private fun ZoomButton(text: String, onClick: () -> Unit, testTag: String) {
  Surface(
    color = MtaColors.CardBg,
    shape = RoundedCornerShape(8.dp),
    modifier = Modifier.clickable(onClick = onClick).testTag(testTag),
  ) {
    Text(
      text = text,
      color = Color.White,
      fontSize = 18.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

/** The selected station's details and the buttons that start or end a ride there. */
@Composable
private fun BoxScope.StationDetailSheet(
  station: SubwayStation,
  stats: SubwayStatsSnapshot,
  onStartRideFromStation: (String) -> Unit,
  onEndRideAtStation: (String) -> Unit,
) {
  val isStopped = station.id in stats.visitedStationIdsStoppedAt
  val isBoarded = station.id in stats.visitedStationIdsBoardedExited
  Column(
    modifier =
      Modifier.align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(12.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(MtaColors.CardBg)
        .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(16.dp))
        .padding(16.dp)
        .testTag("station_detail_sheet"),
  ) {
    StationHeader(station)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text =
        when {
          isBoarded -> "Status: Visited (Boarded / Exited / Transferred here)"
          isStopped -> "Status: Stopped Through on a train"
          else -> "Status: Unvisited station"
        },
      color = if (isStopped) MtaColors.AccentGreen else MtaColors.TextSecondary,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = { onStartRideFromStation(station.id) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0039A6)),
        modifier = Modifier.weight(1f),
      ) {
        Text("Log Ride From Here", fontSize = 12.sp)
      }
      Button(
        onClick = { onEndRideAtStation(station.id) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00933C)),
        modifier = Modifier.weight(1f),
      ) {
        Text("Log Ride To Here", fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun StationHeader(station: SubwayStation) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = station.name,
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = "${station.borough.displayName} • Complex: ${station.complexName}",
        color = MtaColors.TextSecondary,
        fontSize = 12.sp,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      station.daytimeRoutes.forEach { rId ->
        MtaRouteBullet(routeId = rId, size = 24.dp, fontSize = 11.sp)
      }
    }
  }
}
