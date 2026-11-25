package com.straphanger.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.map.MapViewport
import com.straphanger.app.ui.map.initialMapView
import com.straphanger.app.ui.map.projectGeometry
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The map's colour mode, route chips, zoom buttons and station sheet. */
class MapControlsScreenTest : AppScreenTest() {
  @Test
  fun colourModeAndRouteChipsShowWhichOneIsSelected() {
    launchApp()
    openTab("MAP")
    composeRule.onNodeWithTag("map_mode_OFFICIAL_MTA_COLORS").assertIsSelected()
    composeRule.onNodeWithTag("map_mode_FREQUENCY_HEATMAP").assertIsNotSelected()
    composeRule.onNodeWithTag("filter_route_ALL").assertIsSelected()
    composeRule.onNodeWithTag("filter_route_4").assertContentDescriptionEquals("4 line")
    composeRule.onNodeWithTag("filter_route_4").assertIsNotSelected().performClick()
    composeRule.onNodeWithTag("filter_route_4").assertIsSelected()
    composeRule.onNodeWithTag("filter_route_ALL").assertIsNotSelected()
  }

  @Test
  fun zoomButtonsAreIconsWithDescriptions() {
    launchApp()
    openTab("MAP")
    composeRule.onNodeWithTag("map_zoom_in").assertContentDescriptionEquals("Zoom in")
    composeRule.onNodeWithTag("map_zoom_out").assertContentDescriptionEquals("Zoom out")
  }

  @Test
  fun closingTheStationSheetClearsTheSelection() {
    val vm = launchApp()
    openTab("MAP")
    val canvas = composeRule.onNodeWithTag("subway_map_canvas").fetchSemanticsNode().size
    val density = composeRule.activity.resources.displayMetrics.density
    val geometry = projectGeometry(BundledNetwork.data)
    val opening =
      initialMapView(geometry, canvas.width.toFloat(), canvas.height.toFloat(), density)!!
    val viewport = MapViewport(canvas.width.toFloat(), canvas.height.toFloat(), opening)
    val centre = Offset(canvas.width / 2f, canvas.height / 2f)
    val target =
      geometry.stations.minBy { (viewport.toScreen(it.normX, it.normY) - centre).getDistance() }
    val tap = viewport.toScreen(target.normX, target.normY)
    composeRule.onNodeWithTag("subway_map_canvas").performTouchInput { click(tap) }
    composeRule.onNodeWithTag("station_detail_sheet").assertIsDisplayed()

    composeRule.onNodeWithTag("station_detail_close").performClick()

    composeRule.onNodeWithTag("station_detail_sheet").assertDoesNotExist()
    assertNull(vm.uiState.value.selectedStationId)
    assertEquals("Line colors", vm.uiState.value.mapColorMode.label)
  }

  @Test
  fun loggingARideFromTheStationSheetClosesItForWhenTheRiderComesBack() {
    val vm = launchApp()
    openTab("MAP")
    composeRule.runOnUiThread { vm.selectStationOnMap(BundledNetwork.data.stations.first().id) }
    composeRule.onNodeWithTag("station_detail_sheet").assertIsDisplayed()

    composeRule.onNodeWithText("Log Ride From Here").performClick()
    composeRule.onNodeWithTag("log_ride_sheet").assertIsDisplayed()
    assertNull(vm.uiState.value.selectedStationId)

    openTab("MAP")
    composeRule.onNodeWithTag("station_detail_sheet").assertDoesNotExist()
  }
}
