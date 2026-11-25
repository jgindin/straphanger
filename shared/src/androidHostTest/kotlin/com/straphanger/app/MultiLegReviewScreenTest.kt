package com.straphanger.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.testutil.BundledNetwork
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.Test

/** Reviewing express or local on a ride with a change: one choice group per leg that asks. */
class MultiLegReviewScreenTest : AppScreenTest() {
  private val engine = BundledNetwork.engine()
  private val nineAm = Instant.parse("2026-11-11T14:00:00Z").toEpochMilliseconds()
  private val inBanner = hasAnyAncestor(hasTestTag("needs_review_banner"))

  private fun trackedRide(id: String, origin: String, destination: String, minutes: Int): CompleteRideRecord =
    SubwayDao.buildCompleteRideRecord(
      rideId = id,
      startTimeEpochMs = nineAm,
      endTimeEpochMs = nineAm + minutes * 60_000L,
      source = RideSource.PASSIVE_GPS,
      resolved =
        engine.resolveOriginToDestinationAuto(
          originStationId = origin,
          destinationStationId = destination,
          timestampEpochMs = nineAm,
          actualDurationMinutes = minutes.toDouble(),
        ),
      graphEngine = engine,
    )

  /** The rider's trip: 14 St (A/C/E) to 242 St, the A or C to 168 St, then the 1. */
  private fun ridersTrip() = trackedRide("trip", "A31", "101", minutes = 55)

  private fun legsOf(vm: com.straphanger.app.ui.StraphangerViewModel, id: String) =
    vm.dao.getRideById(id)!!.legs.map { it.routeId to it.isExpress }

  @Test
  fun aRideWithAChangeAsksAboutItsFirstLegAndOneTapConfirms() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.runOnUiThread { vm.dao.insertOrReplaceRide(ridersTrip()) }

    composeRule
      .onNode(hasTestTag("review_leg_trip_leg0") and inBanner, useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals("From 14 St:")
    composeRule
      .onNode(hasTestTag("confirm_candidate_trip_leg0_0") and inBanner)
      .assertTextContains("A Express", substring = true)
    val local = hasTestTag("confirm_candidate_trip_leg0_1") and inBanner
    composeRule.onNode(local).assertTextContains("C Local", substring = true).performClick()

    assertEquals(listOf("C" to false, "1" to false), legsOf(vm, "trip"))
    assertFalse(vm.dao.getRideById("trip")!!.ride.needsReview)
    composeRule.onNodeWithTag("needs_review_banner").assertDoesNotExist()
  }

  @Test
  fun aRideWithTwoLegsThatAskIsStoredOnceBothAreChosen() {
    val vm = launchApp()
    openTab("RIDES")
    // 59 St-Columbus Circle to 14 St-Union Sq: the A or C to 42 St, then the N or R.
    composeRule.runOnUiThread { vm.dao.insertOrReplaceRide(trackedRide("two", "A24", "635", 25)) }
    composeRule.onNode(hasTestTag("review_leg_two_leg1") and inBanner, useUnmergedTree = true)
      .assertIsDisplayed()

    val local = hasTestTag("confirm_candidate_two_leg0_1") and inBanner
    composeRule.onNode(local).performClick()
    composeRule.onNode(local).assertIsSelected()
    composeRule.onNode(hasTestTag("confirm_candidate_two_leg1_0") and inBanner).assertIsNotSelected()
    assertTrue(vm.dao.getRideById("two")!!.ride.needsReview)
    // The ride's own row shows the same choice, and it is still there after switching tabs.
    val inRow = hasAnyAncestor(hasTestTag("ride_item_two"))
    composeRule.onNode(hasTestTag("confirm_candidate_two_leg0_1") and inRow).performScrollTo().assertIsSelected()
    openTab("MAP")
    openTab("RIDES")
    composeRule.onNode(local).assertIsSelected()
    composeRule
      .onNode(hasTestTag("confirm_candidate_two_leg0_1") and inRow)
      .performScrollTo()
      .assertIsSelected()

    composeRule.onNode(hasTestTag("confirm_candidate_two_leg1_0") and inBanner).performClick()

    assertEquals(listOf("C" to false, "N" to true), legsOf(vm, "two"))
    assertFalse(vm.dao.getRideById("two")!!.ride.needsReview)
  }

  @Test
  fun theStationEditorStoresTheExpressChosenForTheFirstLeg() {
    val vm = launchApp()
    openTab("RIDES")
    composeRule.runOnUiThread { vm.dao.insertOrReplaceRide(trackedRide("trip", "A31", "101", 90)) }
    // At 90 minutes the app guesses the local.
    assertEquals("C", legsOf(vm, "trip").first().first)

    composeRule.onNodeWithTag("review_edit_stations_trip").performScrollTo().performClick()
    composeRule
      .onNodeWithTag("edit_stations_leg_0", useUnmergedTree = true)
      .assertTextEquals("From 14 St")
    composeRule.onNodeWithTag("edit_stations_route_leg0_0").performClick()
    composeRule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(listOf("A" to true, "1" to false), legsOf(vm, "trip"))
    assertFalse(vm.dao.getRideById("trip")!!.ride.needsReview)
  }
}
