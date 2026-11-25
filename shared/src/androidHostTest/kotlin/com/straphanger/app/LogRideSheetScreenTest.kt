package com.straphanger.app

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.straphanger.app.ui.StationIds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The Rides tab's Log ride button and the log sheet: station search, your trips, logging. */
class LogRideSheetScreenTest : AppScreenTest() {
  @Test
  fun theLogRideButtonOpensTheSheetAndSwipingDownClosesIt() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("log_ride_sheet").assertDoesNotExist()

    composeRule.onNodeWithTag("log_ride_fab").performClick()

    composeRule.onNodeWithTag("log_ride_sheet").assertIsDisplayed()
    assertTrue(vm.uiState.value.isLogRideSheetOpen)
    composeRule.onNodeWithTag("log_from_field").assertIsDisplayed()
    composeRule.onNodeWithTag("log_transfer_field").assertIsDisplayed()
    composeRule.onNodeWithTag("log_to_field").assertIsDisplayed()

    closeLogRideSheet()
    composeRule.onNodeWithTag("log_ride_sheet").assertDoesNotExist()
    assertFalse(vm.uiState.value.isLogRideSheetOpen)
  }

  @Test
  fun theFormStartsEmptyAndLogRideWaitsForTwoDifferentStations() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.runOnUiThread {
      vm.updateLogForm(StationIds.UNION_SQ_LEX, StationIds.UNION_SQ_L, StationIds.BEDFORD_AV_L)
    }
    composeRule.onNodeWithTag("log_ride_fab").performClick()

    composeRule.onNodeWithTag("log_from_field").assertContentDescriptionEquals("From: none")
    composeRule
      .onNodeWithTag("log_transfer_field")
      .assertContentDescriptionEquals("Change at (optional): none")
    composeRule.onNodeWithTag("log_to_field").assertContentDescriptionEquals("To: none")
    composeRule.onNodeWithTag("submit_log_ride_btn").assertIsNotEnabled()

    pickStation("log_from_field", StationIds.UNION_SQ_LEX)
    composeRule.onNodeWithTag("submit_log_ride_btn").assertIsNotEnabled()
    pickStation("log_to_field", StationIds.UNION_SQ_LEX)
    composeRule.onNodeWithTag("submit_log_ride_btn").assertIsNotEnabled()
    pickStation("log_to_field", StationIds.EIGHTY_SIXTH_LEX)
    composeRule.onNodeWithTag("submit_log_ride_btn").assertIsEnabled()
  }

  @Test
  fun choosingStationsThroughSearchAndLoggingAddsTheRideAndClosesTheSheet() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("log_ride_fab").performClick()

    pickStation("log_from_field", StationIds.UNION_SQ_LEX)
    pickStation("log_to_field", StationIds.EIGHTY_SIXTH_LEX)
    composeRule
      .onNodeWithTag("log_to_field")
      .assertContentDescriptionEquals("To: 86 St")
    composeRule.onNodeWithTag("submit_log_ride_btn").performClick()

    composeRule.onNodeWithTag("log_ride_sheet").assertDoesNotExist()
    val ride = vm.uiState.value.rides.single().ride
    assertEquals(StationIds.UNION_SQ_LEX, ride.originStationId)
    assertEquals(StationIds.EIGHTY_SIXTH_LEX, ride.destinationStationId)
    composeRule.onNodeWithTag("ride_item_${ride.rideId}").assertIsDisplayed()
  }

  @Test
  fun theChangeCanBeChosenAndCleared() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("log_ride_fab").performClick()

    pickStation("log_transfer_field", StationIds.UNION_SQ_L)
    assertEquals(StationIds.UNION_SQ_L, vm.uiState.value.logTransferStationId)

    composeRule.onNodeWithTag("log_transfer_field").performClick()
    composeRule.onNodeWithTag("log_station_clear").performClick()
    composeRule.onNodeWithTag("log_station_search").assertDoesNotExist()
    assertNull(vm.uiState.value.logTransferStationId)
  }

  @Test
  fun yourTripsAreTheRidersOwnAndFillInTheForm() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("log_ride_fab").performClick()
    composeRule.onNodeWithTag("trip_suggestion_0").assertDoesNotExist()
    closeLogRideSheet()

    logRideThroughSheet(StationIds.TIMES_SQ_SHUTTLE, StationIds.GRAND_CENTRAL_SHUTTLE)
    composeRule.runOnUiThread {
      vm.updateLogForm(
        originId = StationIds.UNION_SQ_LEX,
        destinationId = StationIds.EIGHTY_SIXTH_LEX,
      )
    }
    composeRule.onNodeWithTag("log_ride_fab").performClick()
    composeRule.onNodeWithTag("trip_suggestion_0").performClick()

    with(vm.uiState.value) {
      assertEquals(StationIds.TIMES_SQ_SHUTTLE, logOriginStationId)
      assertEquals(StationIds.GRAND_CENTRAL_SHUTTLE, logDestinationStationId)
      assertNull(logTransferStationId)
    }
    composeRule
      .onNodeWithTag("log_from_field")
      .assertContentDescriptionEquals("From: Times Sq-42 St")
    assertEquals("GS", vm.uiState.value.logPreferredRouteId, "always the shuttle")

    // A station chosen by hand drops the trip's line, which belonged to the old stations.
    pickStation("log_to_field", StationIds.EIGHTY_SIXTH_LEX)
    assertNull(vm.uiState.value.logPreferredRouteId)
    assertNull(vm.uiState.value.logForceExpress)
  }

  @Test
  fun withNoRidesTheListSaysHowToAddOne() {
    launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("rides_empty").assertIsDisplayed()
    composeRule.onNodeWithTag("rides_count").assertDoesNotExist()

    logRideThroughSheet(StationIds.TIMES_SQ_SHUTTLE, StationIds.GRAND_CENTRAL_SHUTTLE)
    composeRule.onNodeWithTag("rides_empty").assertDoesNotExist()
    composeRule.onNodeWithTag("ride_day_heading").assertIsDisplayed()
  }
}
