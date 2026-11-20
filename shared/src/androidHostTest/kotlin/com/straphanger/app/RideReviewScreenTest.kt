package com.straphanger.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.StationIds
import com.straphanger.app.ui.rides.rideWhen
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant
import org.junit.Test

/** Reviewing rides on the Rides tab: "Looks right", when each happened, combining, and the line. */
class RideReviewScreenTest : AppScreenTest() {
  private val engine = BundledNetwork.engine()
  private val nineAm = Instant.parse("2026-11-11T14:00:00Z").toEpochMilliseconds()

  /** A tracked ride on the bundled network, [startMinute] to [endMinute] after 9:00 on a Wednesday. */
  private fun trackedRide(
    id: String,
    origin: String,
    destination: String,
    startMinute: Int,
    endMinute: Int,
  ): CompleteRideRecord {
    val start = nineAm + startMinute * 60_000L
    return SubwayDao.buildCompleteRideRecord(
      rideId = id,
      startTimeEpochMs = start,
      endTimeEpochMs = nineAm + endMinute * 60_000L,
      source = RideSource.PASSIVE_GPS,
      resolved =
        engine.resolveOriginToDestinationAuto(
          originStationId = origin,
          destinationStationId = destination,
          timestampEpochMs = start,
          actualDurationMinutes = (endMinute - startMinute).toDouble(),
        ),
      graphEngine = engine,
    )
  }

  @Test
  fun aRideThatNeedsReviewSaysItLooksRightInItsOwnRow() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.runOnUiThread { vm.simulateAmbiguousPassiveRide() }
    val rideId = vm.uiState.value.rides.single().ride.rideId
    val looksRight =
      hasTestTag("keep_as_is_$rideId") and hasAnyAncestor(hasTestTag("ride_item_$rideId"))

    composeRule.onNode(looksRight).performScrollTo().assert(hasText("Looks right"))
    composeRule
      .onAllNodes(hasTestTagPrefix("confirm_candidate_$rideId") and hasAnyAncestor(hasTestTag("ride_item_$rideId")))
      .assertCountEquals(2)
    composeRule.onNode(looksRight).performClick()

    assertFalse(vm.uiState.value.rides.single().ride.needsReview)
    composeRule.onNodeWithTag("needs_review_banner").assertDoesNotExist()
    composeRule.onNode(looksRight).assertDoesNotExist()
  }

  @Test
  fun eachRideInTheReviewBannerSaysWhenItHappened() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.runOnUiThread { vm.simulateAmbiguousPassiveRide() }
    val ride = vm.uiState.value.rides.single().ride

    composeRule
      .onNodeWithTag("review_when_${ride.rideId}", useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals(rideWhen(ride.startTimeEpochMs, ride.endTimeEpochMs))
  }

  @Test
  fun aRideSplitInTwoCanBeCombinedIntoOne() {
    val vm = launchApp()
    openTab("RIDES")
    val uptown = trackedRide("first", "A09", "A18", startMinute = 0, endMinute = 14)
    val downtown =
      trackedRide("second", "A18", StationIds.FOURTEENTH_ST_8AV, startMinute = 16, endMinute = 40)
    composeRule.runOnUiThread { vm.dao.insertOrReplaceRides(listOf(uptown, downtown)) }
    val combine = hasTestTag("combine_next_first") and hasAnyAncestor(hasTestTag("ride_item_first"))

    composeRule.onNode(combine).performScrollTo().assert(hasText("168 St → 14 St", substring = true))
    composeRule.onNode(combine).performClick()
    composeRule.onNodeWithTag("combine_rides_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("combine_trip", useUnmergedTree = true).assertTextEquals("168 St → 14 St")
    composeRule.onNodeWithTag("confirm_combine_btn").performClick()

    composeRule.onNodeWithTag("combine_rides_dialog").assertDoesNotExist()
    val only = vm.uiState.value.rides.single().ride
    assertEquals("A09" to StationIds.FOURTEENTH_ST_8AV, only.originStationId to only.destinationStationId)
    assertEquals(uptown.ride.startTimeEpochMs, only.startTimeEpochMs)
    assertEquals(downtown.ride.endTimeEpochMs, only.endTimeEpochMs)
    composeRule.onNodeWithTag("ride_item_second").assertDoesNotExist()
  }

  @Test
  fun theStationEditorStoresTheExpressWhenItIsChosen() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.runOnUiThread { vm.simulateAmbiguousPassiveRide() }
    val rideId = vm.uiState.value.rides.single().ride.rideId

    composeRule.onNodeWithTag("review_edit_stations_$rideId").performScrollTo().performClick()
    composeRule.onNodeWithTag("edit_stations_route_auto").assertIsDisplayed()
    composeRule.onNodeWithTag("edit_stations_route_0").assert(hasText("4 Express", substring = true))
    composeRule.onNodeWithTag("edit_stations_route_0").performClick()
    composeRule.onNodeWithTag("edit_stations_save").performClick()

    val saved = vm.uiState.value.rides.single()
    assertEquals("4" to true, saved.legs.single().routeId to saved.legs.single().isExpress)
    assertFalse(saved.ride.needsReview)
  }
}
