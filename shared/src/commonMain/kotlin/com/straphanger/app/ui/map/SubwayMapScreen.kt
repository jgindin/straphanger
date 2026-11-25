package com.straphanger.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.ui.MapColorMode
import com.straphanger.app.ui.StraphangerUiState

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
  // Saveable, so the zoom and pan survive Settings being pushed over the map.
  var gestureView by rememberSaveable(stateSaver = MapView.Saver) { mutableStateOf(null) }
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
        .background(MaterialTheme.colorScheme.background)
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
        onClose = { onStationSelect(null) },
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
