package com.straphanger.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.commute.stationComplexes
import com.straphanger.app.data.realtime.Arrival
import com.straphanger.app.data.realtime.ArrivalBoard
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.CommuteTapInbox
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test

class StatusFilterScreenTest : AppScreenTest() {
  private val complexes = stationComplexes(BundledNetwork.data)
  private val unionSq = complexes.first { it.name.contains("14 St-Union Sq") }
  private val other = complexes.first { it.complexId != unionSq.complexId }

  @Before
  fun fakeArrivals() {
    val station = unionSq.stationIds.first()
    realtime = {
      RealtimeSnapshot(
        ArrivalBoard(
          listOf(
            Arrival("N", station, Direction.SOUTH, realtimeNow + 120),
            Arrival("NX", station, Direction.SOUTH, realtimeNow + 180),
            Arrival("N", station, Direction.NORTH, realtimeNow + 60),
            Arrival("Q", station, Direction.SOUTH, realtimeNow + 90),
          )
        ),
        emptyList(),
        realtimeNow,
        false,
      )
    }
    CommuteTapInbox.consume()
  }

  private fun seeTrains() {
    launchApp()
    composeRule.runOnUiThread {
      CommuteTapInbox.open(CommuteTap(unionSq.complexId, "N", Direction.SOUTH))
    }
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("status_arrival_N").fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun count(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

  @Test
  fun seeTrainsOpensStatusFilteredToLineAndDirection() {
    seeTrains()

    composeRule
      .onNodeWithTag("status_filter_clear")
      .assertTextContains("Showing N Southbound · Show all")
    assertEquals(1, count("status_arrival_N"))
    assertEquals(1, count("status_arrival_NX"))
    assertEquals(0, count("status_arrival_Q"))
  }

  @Test
  fun showAllClearsTheFilter() {
    seeTrains()

    composeRule.onNodeWithTag("status_filter_clear").performClick()

    assertEquals(0, count("status_filter_clear"))
    assertEquals(2, count("status_arrival_N"))
    assertEquals(1, count("status_arrival_Q"))
  }

  @Test
  fun pickingAnotherStationClearsTheFilter() {
    seeTrains()

    composeRule.onNodeWithTag("status_search").performTextInput(other.name)
    composeRule.onNodeWithTag("status_station_${other.complexId}").performClick()

    assertEquals(0, count("status_filter_clear"))
  }

  @Test
  fun seeTrainsFilterIsAppliedOnce() {
    seeTrains()
    composeRule.onNodeWithTag("status_filter_clear").performClick()

    openTab("MAP")
    openTab("STATUS")
    composeRule.waitForIdle()

    assertEquals(0, count("status_filter_clear"))
  }

  @Test
  fun seeTrainsClosesTheAboutScreen() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("about_screen").assertExists()

    composeRule.runOnUiThread {
      CommuteTapInbox.open(CommuteTap(unionSq.complexId, "N", Direction.SOUTH))
    }
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("status_arrival_N").fetchSemanticsNodes().isNotEmpty()
    }

    assertEquals(0, count("about_screen"))
  }
}
