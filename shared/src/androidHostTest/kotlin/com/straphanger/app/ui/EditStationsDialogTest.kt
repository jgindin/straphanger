package com.straphanger.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RouteChoice
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.rides.EditStationsDialog
import kotlin.test.assertEquals
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The station editor on its own. Under Robolectric the full app never goes idle while the search
 * field is open over it, so the dialog is tested apart from the screen.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class EditStationsDialogTest {
  @get:Rule val rule = createComposeRule()

  private val engine = BundledNetwork.engine()
  private val ride =
    RideEntity(
      rideId = "r1",
      startTimeEpochMs = 0L,
      endTimeEpochMs = 600_000L,
      originStationId = StationIds.UNION_SQ_LEX,
      destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
      totalDistanceMiles = 3.5,
      durationMinutes = 10.0,
      source = RideSource.PASSIVE_GPS,
    )

  @Test
  fun savingWithoutChangesReturnsTheRidesOwnStations() {
    var saved: Pair<String, String>? = null
    rule.setContent {
      EditStationsDialog(ride, engine, { o, d, _ -> true.also { saved = o to d } }, {})
    }
    rule.onNodeWithTag("edit_stations_dialog").assertIsDisplayed()

    rule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(StationIds.UNION_SQ_LEX to StationIds.EIGHTY_SIXTH_LEX, saved)
  }

  @Test
  fun pickingAStationForEachEndSavesThatPair() {
    var saved: Pair<String, String>? = null
    rule.setContent {
      EditStationsDialog(ride, engine, { o, d, _ -> true.also { saved = o to d } }, {})
    }

    rule.onNodeWithTag("edit_stations_search").performTextInput("125 St")
    val newStart = StationIds.HARLEM_125_8AV
    rule.onNodeWithTag("edit_stations_option_$newStart").performClick()
    rule.onNodeWithTag("edit_stations_choose_end").performClick()
    rule.onNodeWithTag("edit_stations_search").performTextInput("Union Sq")
    val newEnd = StationIds.UNION_SQ_LEX
    rule.onNodeWithTag("edit_stations_option_$newEnd").performClick()
    rule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(newStart to newEnd, saved)
  }

  @Test
  fun searchingFindsTheStationNearestTheRidesOwnAmongThoseWithTheSameName() {
    var saved: Pair<String, String>? = null
    val fromEighthAv = ride.copy(originStationId = "A30", destinationStationId = "A09")
    rule.setContent {
      EditStationsDialog(fromEighthAv, engine, { o, d, _ -> true.also { saved = o to d } }, {})
    }

    rule.onNodeWithTag("edit_stations_search").performTextInput("14")
    rule.onNodeWithTag("edit_stations_option_${StationIds.FOURTEENTH_ST_8AV}").performClick()
    rule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(StationIds.FOURTEENTH_ST_8AV to "A09", saved)
  }

  @Test
  fun aPairThatCannotBeSavedKeepsTheDialogOpenAndSaysSo() {
    rule.setContent { EditStationsDialog(ride, engine, { _, _, _ -> false }, {}) }

    rule.onNodeWithTag("edit_stations_save").performClick()

    rule.onNodeWithTag("edit_stations_error").assertIsDisplayed()
    rule.onNodeWithTag("edit_stations_dialog").assertIsDisplayed()
  }

  @Test
  fun cancelDismissesWithoutSaving() {
    var dismissed = false
    var saved = false
    rule.setContent {
      EditStationsDialog(ride, engine, { _, _, _ -> true.also { saved = true } }, { dismissed = true })
    }

    rule.onNodeWithTag("edit_stations_cancel").performClick()

    assertEquals(true to false, dismissed to saved)
  }

  // 9:00 on a Wednesday in New York, when the A runs express and the C local.
  private val weekdayMorning = Instant.parse("2026-11-11T14:00:00Z").toEpochMilliseconds()
  private val eighthAv =
    ride.copy(
      originStationId = "A09",
      destinationStationId = StationIds.FOURTEENTH_ST_8AV,
      startTimeEpochMs = weekdayMorning,
      endTimeEpochMs = weekdayMorning + 30 * 60_000L,
      durationMinutes = 30.0,
    )

  @Test
  fun aTripWithAnExpressAndALocalOffersEachAndLetsTheAppDecideByDefault() {
    var saved: Map<Int, RouteChoice>? = null
    rule.setContent { EditStationsDialog(eighthAv, engine, { _, _, c -> true.also { saved = c } }, {}) }

    rule.onNodeWithTag("edit_stations_route_0").assertTextContains("A Express")
    rule.onNodeWithTag("edit_stations_route_1").assertTextContains("C Local")
    rule.onNodeWithTag("edit_stations_route_auto", useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(emptyMap(), saved)
  }

  @Test
  fun choosingTheExpressSavesIt() {
    var saved: Map<Int, RouteChoice>? = null
    rule.setContent { EditStationsDialog(eighthAv, engine, { _, _, c -> true.also { saved = c } }, {}) }

    rule.onNodeWithTag("edit_stations_route_0").performClick()
    rule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(mapOf(0 to RouteChoice("A", true)), saved)
  }

  @Test
  fun theRidesOwnLineIsChosenWhileTheStationsAreUnchanged() {
    var saved: Map<Int, RouteChoice>? = null
    rule.setContent {
      EditStationsDialog(
        eighthAv,
        engine,
        { _, _, c -> true.also { saved = c } },
        {},
        currentRoutes = mapOf(0 to RouteChoice("C", false)),
      )
    }

    rule.onNodeWithTag("edit_stations_save").performClick()

    assertEquals(mapOf(0 to RouteChoice("C", false)), saved)
  }

  @Test
  fun aTripWithOneWayToRideOffersNoLines() {
    val shuttle =
      eighthAv.copy(
        originStationId = StationIds.TIMES_SQ_SHUTTLE,
        destinationStationId = StationIds.GRAND_CENTRAL_SHUTTLE,
      )
    rule.setContent { EditStationsDialog(shuttle, engine, { _, _, _ -> true }, {}) }

    rule.onNodeWithTag("edit_stations_dialog").assertIsDisplayed()
    rule.onNodeWithTag("edit_stations_route_auto").assertDoesNotExist()
  }
}
