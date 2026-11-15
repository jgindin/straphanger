package com.straphanger.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.about.NOT_AFFILIATED_TEXT
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The Import & Backup tab and the About screen. */
class SourcesAndAboutScreenTest : AppScreenTest() {
  @Test
  fun importAndBackupShowsItsButtonsAndImportsTheSampleTimeline() {
    val vm = launchApp()
    openTab("SOURCES")
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("pick_timeline_file_btn").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("export_backup_btn").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("import_backup_btn").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("open_about_btn").performScrollTo().assertIsDisplayed()

    composeRule.onNodeWithTag("import_sample_timeline_btn").performScrollTo().performClick()
    assertTrue(vm.uiState.value.rides.isNotEmpty())
    assertEquals(vm.uiState.value.rides.size, headerRideCount())
  }

  @Test
  fun aboutScreenShowsDisclaimerVersionAndDataDate() {
    launchApp()
    openTab("SOURCES")
    composeRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("about_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("about_disclaimer").assert(hasText(NOT_AFFILIATED_TEXT))
    composeRule.onNodeWithTag("about_version").assert(hasText("9.8.7", substring = true))
    composeRule
      .onNodeWithTag("about_data_date")
      .assert(hasText("MTA GTFS, ${BundledNetwork.data.sourceDate}", substring = true))
    composeRule
      .onNodeWithTag("about_attribution")
      .assert(hasText(BundledNetwork.data.sourceDate, substring = true))

    composeRule.onNodeWithTag("about_licenses_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("licenses_screen").assertIsDisplayed()
    composeRule.onNodeWithText("Apache License 2.0").assertIsDisplayed()
    composeRule.onNodeWithTag("licenses_back_btn").performClick()
    composeRule.onNodeWithTag("about_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("about_back_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("about_screen").assertDoesNotExist()
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
  }

  @Test
  fun aboutWithoutLicensesDisablesTheLicensesButton() {
    launchApp(withLicenses = false)
    openTab("SOURCES")
    composeRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    composeRule
      .onNodeWithTag("about_licenses_btn")
      .assert(hasText("not listed on this platform", substring = true))
  }

  @Test
  fun systemBackClosesLicensesThenAbout() {
    launchApp()
    openTab("SOURCES")
    composeRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("about_licenses_btn").performScrollTo().performClick()
    composeRule.onNodeWithTag("licenses_screen").assertIsDisplayed()

    pressBack()
    composeRule.onNodeWithTag("licenses_screen").assertDoesNotExist()
    composeRule.onNodeWithTag("about_screen").assertIsDisplayed()

    pressBack()
    composeRule.onNodeWithTag("about_screen").assertDoesNotExist()
    composeRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
  }
}
