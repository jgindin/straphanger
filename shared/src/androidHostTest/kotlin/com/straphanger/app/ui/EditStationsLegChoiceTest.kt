package com.straphanger.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.RouteChoice
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.rides.EditStationsDialog
import kotlin.test.assertEquals
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The station editor on a trip with a change: a "Which train?" group for each leg that asks. */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class EditStationsLegChoiceTest {
  @get:Rule val rule = createComposeRule()

  private val engine = BundledNetwork.engine()
  private val weekdayMorning = Instant.parse("2026-11-11T14:00:00Z").toEpochMilliseconds()

  // 14 St (A/C/E) to 168 St, a 55-minute ride that really went on to 242 St.
  private val ride =
    RideEntity(
      rideId = "r1",
      startTimeEpochMs = weekdayMorning,
      endTimeEpochMs = weekdayMorning + 55 * 60_000L,
      originStationId = StationIds.FOURTEENTH_ST_8AV,
      destinationStationId = "A09",
      totalDistanceMiles = 8.0,
      durationMinutes = 55.0,
      source = RideSource.PASSIVE_GPS,
    )

  @Test
  fun editingTheTripTo242StOffersATrainFor14StAndSavesTheExpress() {
    var saved: Map<Int, RouteChoice>? = null
    rule.setContent { EditStationsDialog(ride, engine, { _, _, c -> true.also { saved = c } }, {}) }

    rule.onNodeWithTag("edit_stations_choose_end").performClick()
    rule.onNodeWithTag("edit_stations_search").performTextInput("242")
    rule.onNodeWithTag("edit_stations_option_101").performClick()

    rule
      .onNodeWithTag("edit_stations_leg_0", useUnmergedTree = true)
      .assertIsDisplayed()
      .assertTextEquals("From 14 St")
    rule.onNodeWithTag("edit_stations_leg_1").assertDoesNotExist()
    rule.onNodeWithTag("edit_stations_route_leg0_0").assertTextContains("A Express")
    rule.onNodeWithTag("edit_stations_route_leg0_1").assertTextContains("C Local")
    rule.onNodeWithTag("edit_stations_route_leg0_0").performClick()
    rule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(mapOf(0 to RouteChoice("A", true)), saved)
  }

  @Test
  fun lettingTheAppDecideSavesNoChoiceForTheLeg() {
    var saved: Map<Int, RouteChoice>? = null
    val toVanCortlandt = ride.copy(destinationStationId = "101")
    rule.setContent {
      EditStationsDialog(toVanCortlandt, engine, { _, _, c -> true.also { saved = c } }, {})
    }

    rule.onNodeWithTag("edit_stations_route_leg0_1").performClick()
    rule.onNodeWithTag("edit_stations_route_leg0_auto").performClick()
    rule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(emptyMap(), saved)
  }
}
