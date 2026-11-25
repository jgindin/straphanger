package com.straphanger.app

import com.straphanger.app.testutil.TestBasemap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.StraphangerViewModel
import com.straphanger.app.ui.map.MapViewport
import com.straphanger.app.ui.map.initialMapView
import com.straphanger.app.ui.map.projectGeometry
import com.straphanger.app.ui.theme.MtaColors
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

/**
 * The Map and Stats tabs, and what the app shows when the network or the saved rides fail to load.
 */
class MapAndStatsScreenTest : AppScreenTest() {
  /** Draws the map canvas; Compose under Robolectric only draws when asked for pixels. */
  private fun drawMap() {
    val image = composeRule.onNodeWithTag("subway_map_canvas").captureToImage()
    assertTrue(image.width > 0 && image.height > 0)
  }

  @Test
  fun mapScreenRendersAndSwitchesModesAndFilters() {
    val vm = launchApp()
    openTab("MAP")
    composeRule.onNodeWithTag("straphanger_root").assertIsDisplayed()
    composeRule.onNodeWithTag("subway_map_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("subway_map_canvas").assertIsDisplayed()
    // A ride, so that visited lines and stations are drawn too.
    composeRule.runOnUiThread { vm.loadDemoRides() }
    drawMap()

    composeRule.onNodeWithTag("map_mode_FREQUENCY_HEATMAP").performClick()
    assertEquals("FREQUENCY_HEATMAP", vm.uiState.value.mapColorMode.name)
    drawMap()
    composeRule.onNodeWithTag("map_mode_OFFICIAL_MTA_COLORS").performClick()
    assertEquals("OFFICIAL_MTA_COLORS", vm.uiState.value.mapColorMode.name)

    composeRule.onNodeWithTag("filter_route_4").performClick()
    assertEquals("4", vm.uiState.value.selectedRouteFilter)
    drawMap()
    composeRule.onNodeWithTag("filter_route_ALL").performClick()
    assertEquals(null, vm.uiState.value.selectedRouteFilter)
  }

  /** How many pixels of the drawn map canvas are exactly [color]. */
  private fun canvasPixels(color: Color): Int {
    val pixels = composeRule.onNodeWithTag("subway_map_canvas").captureToImage().toPixelMap()
    val argb = color.toArgb()
    var count = 0
    for (x in 0 until pixels.width) {
      for (y in 0 until pixels.height) if (pixels[x, y].toArgb() == argb) count++
    }
    return count
  }

  private fun mapPixels(): List<Int> {
    val pixels = composeRule.onNodeWithTag("subway_map_canvas").captureToImage().toPixelMap()
    return List(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width].toArgb() }
  }

  @Test
  fun draggingAfterZoomingInKeepsTheZoom() {
    val vm = launchApp()
    openTab("MAP")
    composeRule.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.basemap != null }
    val opening = mapPixels()
    composeRule.onNodeWithTag("map_zoom_in").performClick()
    composeRule.onNodeWithTag("map_zoom_in").performClick()
    val zoomed = mapPixels()
    assertTrue(zoomed != opening)

    composeRule.onNodeWithTag("subway_map_canvas").performTouchInput {
      swipeLeft(startX = centerX, endX = centerX - 40f)
    }

    // It used to snap back to the opening view on the first drag.
    val dragged = mapPixels()
    assertTrue(dragged != opening, "the drag put the map back at the opening view")
    assertTrue(dragged != zoomed, "the drag did not move the map")
  }

  @Test
  fun basemapIsAlwaysDrawnUnderTheLinesAndHasNoSwitch() {
    val vm = launchApp()
    openTab("MAP")
    composeRule.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.basemap != null }
    composeRule.onNodeWithTag("map_basemap_toggle").assertDoesNotExist()
    // The opening view shows the whole network, so land, water and parks all appear.
    assertTrue(canvasPixels(MtaColors.BasemapWater) > 10_000)
    assertTrue(canvasPixels(MtaColors.BasemapLand) > 10_000)
    assertTrue(canvasPixels(MtaColors.BasemapPark) > 500)
  }

  @Test
  fun tappingAStationOpensItsCardAndLogsARideFromOrToIt() {
    val vm = launchApp()
    openTab("MAP")
    composeRule.onNodeWithTag("station_detail_sheet").assertDoesNotExist()

    // Tap the station drawn nearest the canvas centre, using the screen's own projection and the
    // view it opens with.
    val canvas = composeRule.onNodeWithTag("subway_map_canvas").fetchSemanticsNode().size
    val density = composeRule.activity.resources.displayMetrics.density
    val geometry = projectGeometry(BundledNetwork.data)
    val opening =
      initialMapView(geometry, canvas.width.toFloat(), canvas.height.toFloat(), density)!!
    val viewport = MapViewport(canvas.width.toFloat(), canvas.height.toFloat(), opening)
    val centre = Offset(canvas.width / 2f, canvas.height / 2f)
    val target =
      geometry.stations.minBy { ps ->
        (viewport.toScreen(ps.normX, ps.normY) - centre).getDistance()
      }
    val tap = viewport.toScreen(target.normX, target.normY)
    composeRule.onNodeWithTag("subway_map_canvas").performTouchInput { click(tap) }

    composeRule.onNodeWithTag("station_detail_sheet").assertIsDisplayed()
    val selected = vm.uiState.value.selectedStationId!!
    val selectedName = BundledNetwork.data.stations.first { it.id == selected }.name
    composeRule
      .onNodeWithTag("station_detail_sheet")
      .assert(hasAnyDescendant(hasText(selectedName)))

    composeRule.onNodeWithText("Log Ride From Here").performClick()
    composeRule.onNodeWithTag("rides_history_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("log_ride_sheet").assertIsDisplayed()
    assertEquals(selected, vm.uiState.value.logOriginStationId)
    assertEquals(null, vm.uiState.value.logTransferStationId)

    // Closing the sheet leaves the station chosen on the map.
    closeLogRideSheet()
    composeRule.onNodeWithTag("log_ride_sheet").assertDoesNotExist()
    openTab("MAP")
    composeRule.onNodeWithText("Log Ride To Here").performClick()
    composeRule.onNodeWithTag("log_ride_sheet").assertIsDisplayed()
    assertEquals(selected, vm.uiState.value.logDestinationStationId)
    assertEquals(null, vm.uiState.value.logOriginStationId, "only the tapped end is filled in")
  }

  @Test
  fun statsScreenShowsCardsAndTogglesGroupingAndVisitFilter() {
    val vm = launchApp()
    openTab("STATS")
    composeRule.onNodeWithTag("stats_dashboard_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("stat_card_total_rides").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("stat_card_unique_miles").performScrollTo().assertIsDisplayed()

    composeRule.onNodeWithTag("toggle_grouping_STATION_COMPLEXES").performScrollTo().performClick()
    assertEquals("STATION_COMPLEXES", vm.uiState.value.stationGroupingMode.name)
    composeRule
      .onNodeWithTag("toggle_visit_BOARDED_OR_EXITED_ONLY")
      .performScrollTo()
      .performClick()
    assertEquals("BOARDED_OR_EXITED_ONLY", vm.uiState.value.stationVisitFilterMode.name)
    composeRule
      .onNodeWithTag("toggle_grouping_INDIVIDUAL_STATIONS")
      .performScrollTo()
      .performClick()
    assertEquals("INDIVIDUAL_STATIONS", vm.uiState.value.stationGroupingMode.name)
    composeRule.onNodeWithTag("toggle_visit_STOPPED_AT").performScrollTo().performClick()
    assertEquals("STOPPED_AT", vm.uiState.value.stationVisitFilterMode.name)
  }

  @Test
  fun unreadableSavedRidesShowTheStorageErrorBanner() {
    launchApp(newViewModel(InMemoryPersistenceBackend(initialJson = "{ not json")))
    composeRule.onNodeWithTag("storage_error_banner").assertIsDisplayed()
    assertEquals(0, statsRideCount())
  }

  @Test
  fun readableSavedRidesShowNoStorageErrorBanner() {
    launchApp()
    composeRule.onNodeWithTag("storage_error_banner").assertDoesNotExist()
  }

  @Test
  fun networkThatFailsToLoadShowsTheErrorInsteadOfTabs() {
    val vm =
      StraphangerViewModel(
          backend = InMemoryPersistenceBackend(),
          networkLoader = { throw IllegalStateException("broken bundle") },
          basemapLoader = { TestBasemap.empty },
          geofenceEvents = emptyFlow(),
          notificationSessionsEnded = emptyFlow(),
        )
        .also { viewModels += it }
    launchApp(vm, awaitNetwork = false)
    composeRule
      .onNodeWithTag("network_load_error")
      .assert(hasText("The subway network could not be loaded: broken bundle"))
    composeRule.onNodeWithTag("subway_map_screen").assertDoesNotExist()
    // Settings needs the network, so the gear is not offered.
    composeRule.onNodeWithTag("top_bar_settings").assertDoesNotExist()
  }
}
