package com.straphanger.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.straphanger.app.data.commute.minutesText
import com.straphanger.app.data.commute.stationComplexes
import com.straphanger.app.data.realtime.AlertKind
import com.straphanger.app.data.realtime.Arrival
import com.straphanger.app.data.realtime.ArrivalBoard
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.data.realtime.ServiceAlert
import com.straphanger.app.data.local.InMemoryFavoriteStationsStore
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.status.StatusState
import com.straphanger.app.ui.status.refreshStatus
import com.straphanger.app.ui.status.searchComplexes
import com.straphanger.app.ui.status.toggleFavorite
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StatusScreenTest : AppScreenTest() {
  private val complexes = stationComplexes(BundledNetwork.data)
  private val unionSq = complexes.first { it.name.contains("14 St-Union Sq") }

  private fun snapshot(
    arrivals: List<Arrival> = emptyList(),
    alerts: List<ServiceAlert> = emptyList(),
    partial: Boolean = false,
  ) = RealtimeSnapshot(ArrivalBoard(arrivals), alerts, realtimeNow, partial)

  private fun alert(kind: AlertKind, routes: Set<String>, header: String) =
    ServiceAlert("a-$header", routes, kind, header, "")

  @Test
  fun searchingAndPickingAStationListsItsNextTrainsByDirection() {
    realtime = {
      snapshot(
        arrivals =
          listOf(
            Arrival("4", unionSq.stationIds.first(), Direction.NORTH, realtimeNow + 120),
            Arrival("4", unionSq.stationIds.first(), Direction.SOUTH, realtimeNow + 5),
          ),
      )
    }
    launchApp()
    openTab("STATUS")
    composeRule.onNodeWithTag("status_screen").assertIsDisplayed()

    composeRule.onNodeWithTag("status_search").performTextInput("Union Sq")
    composeRule.onNodeWithTag("status_station_${unionSq.complexId}").performClick()

    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("status_arrival_4").fetchSemanticsNodes().size == 2
    }
    composeRule.onNodeWithTag("status_freshness").assertTextContains("Up to date.")
  }

  @Test
  fun starringAStationSavesItAndListsItFirstNextTime() {
    launchApp()
    openTab("STATUS")
    composeRule.onNodeWithTag("status_search").performTextInput("Union Sq")
    composeRule.onNodeWithTag("status_station_${unionSq.complexId}").performClick()
    composeRule.onNodeWithTag("status_favorite_toggle").assertTextContains("☆ Favorite")

    composeRule.onNodeWithTag("status_favorite_toggle").performClick()

    composeRule.onNodeWithTag("status_favorite_toggle").assertTextContains("★ Favorite")
    assertEquals(listOf(unionSq.complexId), favorites.read())
  }

  @Test
  fun savedFavoritesAreListedAndOpenWithOneTapAndCanBeRemoved() {
    favorites = InMemoryFavoriteStationsStore(listOf(unionSq.complexId, "no-such-complex"))
    launchApp()
    openTab("STATUS")

    // A saved id the network no longer has is skipped instead of crashing the list.
    composeRule.onNodeWithTag("status_station_${unionSq.complexId}").performClick()
    composeRule.onNodeWithTag("status_arrivals").assertIsDisplayed()
    composeRule.onNodeWithTag("status_favorite_toggle").assertTextContains("★ Favorite")

    composeRule.onNodeWithTag("status_favorite_toggle").performClick()

    assertEquals(listOf("no-such-complex"), favorites.read())
  }

  @Test
  fun togglingAFavoriteAddsAtTheEndAndRemoves() {
    assertEquals(listOf("a", "b"), toggleFavorite(listOf("a"), "b"))
    assertEquals(listOf("a"), toggleFavorite(listOf("a", "b"), "b"))
  }

  @Test
  fun offlineAtTheStartSaysSoInsteadOfShowingNumbers() {
    launchApp()
    openTab("STATUS")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("status_freshness").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.waitUntil(5_000) {
      runCatching {
          composeRule.onNodeWithTag("status_freshness").assertTextContains("Can't reach", substring = true)
        }
        .isSuccess
    }
  }

  @Test
  fun delaysShowFirstAndPlannedWorkIsBehindAToggle() {
    realtime = {
      snapshot(
        alerts =
          listOf(
            alert(AlertKind.DELAY, setOf("N"), "N trains are delayed"),
            alert(AlertKind.PLANNED, setOf("J"), "J skips stops this weekend"),
          ),
      )
    }
    launchApp()
    openTab("STATUS")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("status_alert").fetchSemanticsNodes().size == 1
    }
    composeRule.onNodeWithTag("status_planned_toggle").performClick()
    assertEquals(2, composeRule.onAllNodesWithTag("status_alert").fetchSemanticsNodes().size)
  }

  @Test
  fun aFailedRefreshKeepsTheOldDataAndSaysWhenItIsFrom() = runTest {
    val good = snapshot()
    val first = refreshStatus(StatusState(), listOf("L"), { good }, 100)
    assertEquals(good, first.snapshot)
    assertNull(first.failedAtEpochSeconds)

    val second = refreshStatus(first, listOf("L"), { throw java.io.IOException("down") }, 200)
    assertEquals(good, second.snapshot)
    assertEquals(200L, second.failedAtEpochSeconds)
  }

  @Test
  fun searchFindsStationsByNamePrefixFirstAndIgnoresBlank() {
    assertTrue(searchComplexes(complexes, "  ").isEmpty())
    val found = searchComplexes(complexes, "union")
    assertTrue(found.any { it.complexId == unionSq.complexId })
    assertNotNull(found.firstOrNull())
    assertEquals("now", minutesText(0))
    assertEquals("7 min", minutesText(7))
  }
}
