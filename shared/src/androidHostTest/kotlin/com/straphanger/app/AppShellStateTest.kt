package com.straphanger.app

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

/** What the app shell keeps: the pushed screens across recreation, and a tab's state under them. */
class AppShellStateTest : AppScreenTest() {
  private fun assertTitle(title: String) {
    composeRule.onNodeWithTag("header_title").assertTextEquals(title)
  }

  @Test
  fun pushedScreensSurviveRecreationAndBackPopsThemInOrder() {
    val restoration = StateRestorationTester(composeRule)
    launchApp(restoration = restoration)
    openTab("STATS")
    openSettings()
    composeRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("about_licenses_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("licenses_screen").assertIsDisplayed()

    restoration.emulateSavedInstanceStateRestore()

    composeRule.onNodeWithTag("licenses_screen").assertIsDisplayed()
    assertTitle("Open-source licenses")
    pressBack()
    composeRule.onNodeWithTag("about_screen").assertIsDisplayed()
    assertTitle("About")
    pressBack()
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
    assertTitle("Settings")
    pressBack()
    composeRule.onNodeWithTag("stats_dashboard_screen").assertIsDisplayed()
    assertTitle("Stats")
  }

  @Test
  fun aboutWithoutLicensesNeverPushesABlankScreen() {
    launchApp(withLicenses = false)
    openSettings()
    composeRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("about_licenses_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("licenses_screen").assertDoesNotExist()
    composeRule.onNodeWithTag("about_screen").assertIsDisplayed()
  }

  private fun mapPixels(): List<Int> {
    val pixels = composeRule.onNodeWithTag("subway_map_canvas").captureToImage().toPixelMap()
    return List(pixels.width * pixels.height) {
      pixels[it % pixels.width, it / pixels.width].toArgb()
    }
  }

  @Test
  fun theMapKeepsItsZoomUnderSettings() {
    val vm = launchApp()
    openTab("MAP")
    composeRule.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.basemap != null }
    val opening = mapPixels()
    composeRule.onNodeWithTag("map_zoom_in").performClick()
    composeRule.onNodeWithTag("map_zoom_in").performClick()
    val zoomed = mapPixels()
    assertNotEquals(opening, zoomed)

    openSettings()
    composeRule.onNodeWithTag("top_bar_back").performClick()

    assertEquals(zoomed, mapPixels())
  }

  @Test
  fun badgeTextAndDescription() {
    assertEquals("1", reviewBadgeText(1))
    assertEquals("99", reviewBadgeText(99))
    assertEquals("99+", reviewBadgeText(100))
    assertEquals("1 ride needs review", reviewBadgeDescription(1))
    assertEquals("3 rides need review", reviewBadgeDescription(3))
  }
}
