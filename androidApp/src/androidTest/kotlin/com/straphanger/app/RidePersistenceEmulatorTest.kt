package com.straphanger.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import com.straphanger.app.capture.CaptureRuntimeHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Exercises kotlinx-serialization decoding/encoding of rides.json against the (minified) app. Each
 * test manages its own activity. The test orchestrator clears app data before every test
 * (clearPackageData), so each starts with no rides file.
 *
 * The process keeps one ride capture runtime, which holds the rides in memory, so a second activity
 * alone would not read the file again. [relaunchAsNewProcess] drops that runtime first.
 */
class RidePersistenceEmulatorTest {

  @get:Rule val composeTestRule = createEmptyComposeRule()

  private var scenario: ActivityScenario<MainActivity>? = null

  @After
  fun closeScenario() {
    scenario?.close()
  }

  private fun launch() {
    scenario?.close()
    scenario = ActivityScenario.launch(MainActivity::class.java)
    composeTestRule.waitUntil(timeoutMillis = 15_000) {
      composeTestRule.onAllNodesWithTag("nav_tab_RIDES").fetchSemanticsNodes().isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("network_loading").fetchSemanticsNodes().isEmpty()
    }
    composeTestRule.onNodeWithTag("nav_tab_RIDES").performClick()
  }

  /** Launches the activity as a new process would: its new runtime decodes rides.json from disk. */
  private fun relaunchAsNewProcess() {
    scenario?.close()
    scenario = null
    CaptureRuntimeHolder.resetForTest()
    launch()
  }

  private fun headerRideCount(): Int =
    composeTestRule
      .onNodeWithTag("header_total_rides")
      .onChildAt(0)
      .fetchSemanticsNode()
      .config
      .getOrNull(SemanticsProperties.Text)!!
      .first()
      .text
      .toInt()

  @Test
  fun importingSampleTimelineAddsRides() {
    launch()
    assertEquals(0, headerRideCount())
    composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
    scenario!!.onActivity { it.straphangerViewModel().importSampleGoogleMapsTimeline() }
    composeTestRule.onNodeWithTag("nav_tab_RIDES").performClick()
    composeTestRule.waitUntil(timeoutMillis = 15_000) { headerRideCount() > 0 }
    assertEquals(EXPECTED_SAMPLE_RIDES, headerRideCount())
  }

  @Test
  fun loggedRideIsDecodedFromDiskByAFreshViewModel() {
    launch()
    assertEquals(0, headerRideCount())
    composeTestRule.onNodeWithTag("preset_42_shuttle").performClick()
    composeTestRule.onNodeWithTag("submit_log_ride_btn").performClick()
    assertEquals(1, headerRideCount())

    // A fresh runtime and view model, which must decode rides.json from disk.
    relaunchAsNewProcess()
    composeTestRule.waitUntil(timeoutMillis = 15_000) { headerRideCount() == 1 }
    assertEquals(1, headerRideCount())
  }

  @Test
  fun loggedRideCanBeDeletedAfterConfirming() {
    launch()
    composeTestRule.onNodeWithTag("preset_42_shuttle").performClick()
    composeTestRule.onNodeWithTag("submit_log_ride_btn").performClick()
    assertEquals(1, headerRideCount())

    // Cancel keeps the ride.
    composeTestRule.onNode(hasTestTagPrefix("delete_ride_btn_")).performScrollTo().performClick()
    composeTestRule.onNodeWithTag("cancel_delete_btn").performClick()
    assertEquals(1, headerRideCount())

    composeTestRule.onNode(hasTestTagPrefix("delete_ride_btn_")).performScrollTo().performClick()
    composeTestRule.onNodeWithTag("confirm_delete_btn").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) { headerRideCount() == 0 }
    assertEquals(0, headerRideCount())

    // The deletion was saved: a fresh runtime decodes no rides from disk.
    relaunchAsNewProcess()
    assertEquals(0, headerRideCount())
  }

  private companion object {
    const val EXPECTED_SAMPLE_RIDES = 2
  }
}
