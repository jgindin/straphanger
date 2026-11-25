package com.straphanger.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The app shell: the top app bar, the four tabs and Settings pushed behind the gear. */
class AppShellScreenTest : AppScreenTest() {
  @Test
  fun theAppOpensOnStatus() {
    launchApp()
    composeRule.onNodeWithTag("status_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("nav_tab_STATUS").assertIsSelected()
    composeRule.onNodeWithTag("header_title").assert(hasText("Status"))
  }

  @Test
  fun theBarHasExactlyStatusMapRidesAndStats() {
    launchApp()
    composeRule.onAllNodes(hasTestTagPrefix("nav_tab_")).assertCountEquals(4)
    listOf("STATUS" to "Status", "MAP" to "Map", "RIDES" to "Rides", "STATS" to "Stats").forEach {
      (tag, label) ->
      composeRule.onNodeWithTag("nav_tab_$tag").assert(hasText(label))
    }
  }

  @Test
  fun theGearOpensSettings() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("header_title").assert(hasText("Settings"))
    composeRule.onNodeWithTag("top_bar_settings").assertDoesNotExist()
    composeRule.onNodeWithTag("top_bar_back").assertIsDisplayed()
  }

  @Test
  fun topBarBackFromSettingsReturnsToTheTab() {
    launchApp()
    openTab("STATS")
    openSettings()
    composeRule.onNodeWithTag("top_bar_back").performClick()
    composeRule.onNodeWithTag("sources_sync_screen").assertDoesNotExist()
    composeRule.onNodeWithTag("stats_dashboard_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("header_title").assert(hasText("Stats"))
  }

  @Test
  fun systemBackFromSettingsReturnsToTheTab() {
    launchApp()
    openTab("RIDES")
    openSettings()
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
    pressBack()
    composeRule.onNodeWithTag("sources_sync_screen").assertDoesNotExist()
    composeRule.onNodeWithTag("rides_history_screen").assertIsDisplayed()
  }

  @Test
  fun aboutOpenedFromSettingsGoesBackToSettings() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("header_title").assert(hasText("About"))

    composeRule.onNodeWithTag("top_bar_back").performClick()
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("header_title").assert(hasText("Settings"))

    composeRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    pressBack()
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
  }

  @Test
  fun selectingATabClosesSettings() {
    launchApp()
    openSettings()
    openTab("MAP")
    composeRule.onNodeWithTag("sources_sync_screen").assertDoesNotExist()
    composeRule.onNodeWithTag("subway_map_screen").assertIsDisplayed()
  }

  @Test
  fun theRidesBadgeShowsThePendingReviewCount() {
    val vm = launchApp()
    composeRule.onNodeWithTag("nav_badge_RIDES", useUnmergedTree = true).assertDoesNotExist()
    composeRule.runOnUiThread { vm.simulateAmbiguousPassiveRide() }
    assertEquals(1, vm.uiState.value.stats.pendingReviewCount)
    composeRule
      .onNodeWithTag("nav_badge_RIDES", useUnmergedTree = true)
      .assertContentDescriptionEquals("1 ride needs review")
  }

  @Test
  fun theMapCanvasFillsTheSpaceBetweenTheBars() {
    launchApp()
    openTab("MAP")
    val top = composeRule.onNodeWithTag("top_app_bar").getUnclippedBoundsInRoot()
    val bottom = composeRule.onNodeWithTag("navigation_bar").getUnclippedBoundsInRoot()
    val canvas = composeRule.onNodeWithTag("subway_map_screen").getUnclippedBoundsInRoot()
    assertTrue(abs((top.bottom - canvas.top).value) < 1f, "map top ${canvas.top}, bar ${top.bottom}")
    assertTrue(
      abs((bottom.top - canvas.bottom).value) < 1f,
      "map bottom ${canvas.bottom}, bar ${bottom.top}",
    )
  }
}
