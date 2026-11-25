package com.straphanger.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.ui.StraphangerViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.IOException
import org.junit.Test

/** Deleting a ride by swiping it away, by the screen reader action, and putting it back. */
class RideDeleteScreenTest : AppScreenTest() {
  /** The app on the Rides tab with the three sample rides; returns them and the view model. */
  private fun launchWithRides(): Pair<StraphangerViewModel, List<CompleteRideRecord>> {
    val vm = launchApp()
    composeRule.runOnUiThread { vm.loadDemoRides() }
    openTab("RIDES")
    return vm to vm.uiState.value.rides
  }

  @Test
  fun swipingARideAwayDeletesItAndUndoRestoresItExactly() {
    val (vm, before) = launchWithRides()
    val rec = before.first()

    composeRule.onNodeWithTag("swipe_ride_${rec.ride.rideId}").performTouchInput { swipeLeft() }

    composeRule.onNodeWithTag("ride_item_${rec.ride.rideId}").assertDoesNotExist()
    assertTrue(vm.uiState.value.rides.none { it.ride.rideId == rec.ride.rideId })
    composeRule.onNodeWithText("Ride deleted").assertIsDisplayed()

    composeRule.onNodeWithText("Undo").performClick()

    assertEquals(before, vm.uiState.value.rides)
    composeRule.onNodeWithTag("ride_item_${rec.ride.rideId}").assertIsDisplayed()
  }

  @Test
  fun withoutUndoTheRideStaysDeleted() {
    val (vm, before) = launchWithRides()
    val rec = before.first()

    composeRule.onNodeWithTag("swipe_ride_${rec.ride.rideId}").performTouchInput { swipeLeft() }
    // The snackbar times out with nobody tapping Undo.
    composeRule.mainClock.advanceTimeBy(15_000)
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Ride deleted").assertDoesNotExist()
    assertEquals(before - rec, vm.uiState.value.rides)
  }

  @Test
  fun theScreenReadersDeleteActionDeletesWithTheSameUndo() {
    val (vm, before) = launchWithRides()
    val rec = before.first()
    val item = composeRule.onNodeWithTag("ride_item_${rec.ride.rideId}")
    val action =
      item.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)!!.single {
        it.label == "Delete ride"
      }

    composeRule.runOnUiThread { action.action() }
    composeRule.waitForIdle()

    assertEquals(before - rec, vm.uiState.value.rides)
    composeRule.onNodeWithText("Undo").performClick()
    assertEquals(before, vm.uiState.value.rides)
  }

  @Test
  fun aDeletionThatCannotBeSavedPutsTheRowBackWithoutUndo() {
    val backend = SavesThatCanFail()
    val vm = launchApp(newViewModel(backend))
    composeRule.runOnUiThread { vm.loadDemoRides() }
    openTab("RIDES")
    val before = vm.uiState.value.rides
    val rideId = before.first().ride.rideId
    backend.failing = true

    composeRule.onNodeWithTag("swipe_ride_$rideId").performTouchInput { swipeLeft() }
    composeRule.waitForIdle()

    assertEquals(1, backend.failedSaves, "the delete was tried once")
    assertEquals(before, vm.uiState.value.rides)
    composeRule.onNodeWithTag("ride_item_$rideId").assertIsDisplayed()
    composeRule.onNodeWithText("Ride deleted").assertDoesNotExist()
    composeRule.onNodeWithTag("storage_error_banner").assertIsDisplayed()
  }

  /** Saves in memory until [failing] is set; then every save fails, as a full disk would. */
  private class SavesThatCanFail : RidePersistenceBackend {
    private var stored: String? = null
    var failing = false
    var failedSaves = 0

    override fun loadSnapshotJson(): String? = stored

    override fun saveSnapshotJson(json: String) {
      if (failing) {
        failedSaves++
        throw IOException("disk full")
      }
      stored = json
    }
  }
}
