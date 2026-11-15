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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.MapColorMode
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet

private const val MIN_ZOOM = 0.85f
private const val MAX_ZOOM = 5.0f
private const val ZOOM_STEP = 1.25f
private const val TAP_RADIUS_PX = 48f

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
  onBasemapVisibleChange: (Boolean) -> Unit,
) {
  val projectedGeometry = remember(network) { projectGeometry(network) }
  val basemapPaths = remember(uiState.basemap) { uiState.basemap?.let(::BasemapPaths) }
  var zoom by remember { mutableStateOf(1.65f) }
  var panOffset by remember { mutableStateOf(Offset(-120f, -60f)) }
  val selectedStation =
    remember(uiState.selectedStationId, network) {
      network.stations.firstOrNull { it.id == uiState.selectedStationId }
    }

  Box(modifier = Modifier.fillMaxSize().background(MtaColors.DarkBg).testTag("subway_map_screen")) {
    SubwayMapCanvas(
      geometry = projectedGeometry,
      layers =
        MapLayers(
          stats = uiState.stats,
          colorMode = uiState.mapColorMode,
          selectedRoute = uiState.selectedRouteFilter,
          selectedStationId = uiState.selectedStationId,
        ),
      basemap = basemapPaths?.takeIf { uiState.showBasemap },
      zoom = zoom,
      panOffset = panOffset,
      onTransform = { pan, gestureZoom ->
        zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        panOffset += pan
      },
      onStationSelect = onStationSelect,
    )
    MapControlsOverlay(uiState, onColorModeChange, onRouteFilterSelect)
    MapSideControls(
      onZoomIn = { zoom = (zoom * ZOOM_STEP).coerceAtMost(MAX_ZOOM) },
      onZoomOut = { zoom = (zoom / ZOOM_STEP).coerceAtLeast(MIN_ZOOM) },
      showBasemap = uiState.showBasemap,
      onBasemapVisibleChange = onBasemapVisibleChange,
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
  zoom: Float,
  panOffset: Offset,
  onTransform: (pan: Offset, zoom: Float) -> Unit,
  onStationSelect: (String?) -> Unit,
) {
  Canvas(
    modifier =
      // Clipped: the basemap's fills are drawn with the canvas transformed and would otherwise
      // spill over the app header.
      Modifier.fillMaxSize()
        .clipToBounds()
        .testTag("subway_map_canvas")
        .pointerInput(Unit) {
          detectTransformGestures { _, pan, gestureZoom, _ -> onTransform(pan, gestureZoom) }
        }
        .pointerInput(zoom, panOffset) {
          detectTapGestures { tapPos ->
            val viewport = MapViewport(size.width.toFloat(), size.height.toFloat(), zoom, panOffset)
            geometry.nearestStation(viewport, tapPos)?.let { (nearest, distance) ->
              onStationSelect(nearest.station.id.takeIf { distance <= TAP_RADIUS_PX })
            }
          }
        }
  ) {
    drawSubwayMap(geometry, MapViewport(size.width, size.height, zoom, panOffset), layers, basemap)
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
          .testTag("filter_route_ALL")
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
            .testTag("filter_route_$rId")
      ) {
        MtaRouteBullet(routeId = rId, size = 26.dp, fontSize = 12.sp)
      }
    }
  }
}

/** The + and − buttons, and the basemap switch under them. */
@Composable
private fun BoxScope.MapSideControls(
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
  showBasemap: Boolean,
  onBasemapVisibleChange: (Boolean) -> Unit,
) {
  Column(
    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = Alignment.End,
  ) {
    ZoomButton(text = "+", onClick = onZoomIn, testTag = "map_zoom_in")
    ZoomButton(text = "−", onClick = onZoomOut, testTag = "map_zoom_out")
    BasemapToggle(showBasemap, onBasemapVisibleChange)
  }
}

/** Shows or hides the land, parks and roads under the lines. */
@Composable
private fun BasemapToggle(showBasemap: Boolean, onBasemapVisibleChange: (Boolean) -> Unit) {
  Box(
    modifier =
      Modifier.clip(RoundedCornerShape(8.dp))
        .background(if (showBasemap) Color(0xFF0039A6) else MtaColors.CardBg)
        .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(8.dp))
        .toggleable(value = showBasemap, role = Role.Switch, onValueChange = onBasemapVisibleChange)
        .padding(horizontal = 8.dp, vertical = 6.dp)
        .testTag("map_basemap_toggle"),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "Basemap",
      color = if (showBasemap) Color.White else MtaColors.TextSecondary,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
    )
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
        .testTag("station_detail_sheet")
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
