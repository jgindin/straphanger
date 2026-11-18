package com.straphanger.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.straphanger.app.testutil.BundledNetwork
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.Test

/** The Rides tab: logging, review and deleting rides. */
class RidesScreenTest : AppScreenTest() {
  @Test
  fun loggingAPresetRideAddsItToTheListAndHeader() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("rides_history_screen").assertIsDisplayed()
    assertEquals(0, headerRideCount())

    composeRule.onNodeWithTag("preset_42_shuttle").performClick()
    composeRule.onNodeWithTag("submit_log_ride_btn").performClick()

    assertEquals(1, headerRideCount())
    val ride = vm.uiState.value.rides.single().ride
    // The fixed clock's time, not the wall clock's.
    assertEquals(Instant.parse("2026-11-11T14:00:00Z").toEpochMilliseconds(), ride.startTimeEpochMs)
    composeRule.onNodeWithTag("ride_item_${ride.rideId}").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun aRideCardShowsWhenItHappenedAndExpandsToItsLegs() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("preset_42_shuttle").performClick()
    composeRule.onNodeWithTag("submit_log_ride_btn").performClick()
    val rideId = vm.uiState.value.rides.single().ride.rideId

    composeRule.onNodeWithTag("ride_when_$rideId", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("ride_breakdown_$rideId", useUnmergedTree = true).assertDoesNotExist()

    composeRule.onNodeWithTag("ride_item_$rideId").performClick()
    composeRule.onNodeWithTag("ride_breakdown_$rideId", useUnmergedTree = true).assertIsDisplayed()

    composeRule.onNodeWithTag("ride_item_$rideId").performClick()
    composeRule.onNodeWithTag("ride_breakdown_$rideId", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun anyRideCardOpensTheStationEditor() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("preset_42_shuttle").performClick()
    composeRule.onNodeWithTag("submit_log_ride_btn").performClick()
    val rideId = vm.uiState.value.rides.single().ride.rideId

    composeRule.onNodeWithTag("edit_ride_btn_$rideId").performScrollTo().performClick()

    composeRule.onNodeWithTag("edit_stations_dialog").assertIsDisplayed()
  }

  @Test
  fun editingFromTheReviewBannerMovesTheRideAndKeepsTheOthersAlone() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.runOnUiThread { vm.simulateAmbiguousPassiveRide() }
    val before = vm.uiState.value.rides.single().ride

    composeRule.onNodeWithTag("review_edit_stations_${before.rideId}").performScrollTo().performClick()
    composeRule.onNodeWithTag("edit_stations_choose_start").performClick()
    composeRule.onNodeWithTag("edit_stations_search").performTextInput("86 St")
    val newStart = BundledNetwork.engine().searchStations("86 St").distinctBy { it.name }.first().id
    composeRule.onNodeWithTag("edit_stations_option_$newStart").performClick()
    composeRule.onNodeWithTag("edit_stations_save").performClick()

    composeRule.onNodeWithTag("edit_stations_dialog").assertDoesNotExist()
    val after = vm.uiState.value.rides.single().ride
    assertEquals(newStart, after.originStationId)
    assertEquals(before.destinationStationId, after.destinationStationId)
    assertEquals(before.startTimeEpochMs, after.startTimeEpochMs)
  }

  @Test
  fun needsReviewBannerOffersCandidatesAndClearsOnConfirm() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("needs_review_banner").assertDoesNotExist()

    composeRule.runOnUiThread { vm.simulateAmbiguousPassiveRide() }
    composeRule.onNodeWithTag("needs_review_banner").assertIsDisplayed()
    composeRule.onNodeWithTag("nav_tab_RIDES").assert(hasText("(1)", substring = true))
    composeRule
      .onNode(hasTestTagPrefix("confirm_candidate_") and hasTestTagSuffix("_1"))
      .assertIsDisplayed()

    composeRule
      .onNode(hasTestTagPrefix("confirm_candidate_") and hasTestTagSuffix("_0"))
      .performClick()

    composeRule.onNodeWithTag("needs_review_banner").assertDoesNotExist()
    assertEquals(0, vm.uiState.value.stats.pendingReviewCount)
    assertTrue(vm.uiState.value.rides.none { it.ride.needsReview })
  }

  @Test
  fun deletingARideAsksFirstAndCancelKeepsIt() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("preset_42_shuttle").performClick()
    composeRule.onNodeWithTag("submit_log_ride_btn").performClick()
    val rideId = vm.uiState.value.rides.single().ride.rideId

    composeRule.onNodeWithTag("delete_ride_btn_$rideId").performScrollTo().performClick()
    composeRule.onNodeWithTag("delete_ride_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("cancel_delete_btn").performClick()
    composeRule.onNodeWithTag("delete_ride_dialog").assertDoesNotExist()
    assertEquals(1, vm.uiState.value.rides.size)

    composeRule.onNodeWithTag("delete_ride_btn_$rideId").performScrollTo().performClick()
    composeRule.onNodeWithTag("confirm_delete_btn").performClick()
    composeRule.onNodeWithTag("delete_ride_dialog").assertDoesNotExist()
    composeRule.onNodeWithTag("ride_item_$rideId").assertDoesNotExist()
    assertEquals(0, vm.uiState.value.rides.size)
    assertEquals(0, headerRideCount())
  }

  @Test
  fun theAppHasNoControlsThatFabricateRides() {
    launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("submit_log_ride_btn").assertIsDisplayed()
    composeRule.onNodeWithTag("simulate_passive_ride_btn").assertDoesNotExist()
    composeRule.onNodeWithTag("load_demo_rides_btn").assertDoesNotExist()

    openTab("SOURCES")
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("import_sample_timeline_btn").assertDoesNotExist()
    composeRule.onNodeWithTag("pick_timeline_file_btn").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("export_backup_btn").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("import_backup_btn").performScrollTo().assertIsDisplayed()
  }
}
