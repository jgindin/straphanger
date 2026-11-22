package com.straphanger.app

import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** A tapped commute notification ("See trains") opens the Status tab on its line and direction. */
class CommuteAlertEmulatorTest {

  @get:Rule val composeTestRule = createEmptyComposeRule()

  private var scenario: ActivityScenario<MainActivity>? = null

  @After
  fun closeScenario() {
    scenario?.close()
  }

  @Test
  fun seeTrainsIntentOpensFilteredStatus() {
    val intent =
      Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_COMMUTE_COMPLEX, TIMES_SQ_COMPLEX)
        .putExtra(MainActivity.EXTRA_COMMUTE_ROUTE, "7")
        .putExtra(MainActivity.EXTRA_COMMUTE_DIRECTION, "NORTH")
    scenario = ActivityScenario.launch(intent)
    composeTestRule.waitUntil(timeoutMillis = 15_000) {
      composeTestRule.onAllNodesWithTag("status_filter_clear").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("status_filter_clear")
      .assertTextContains("Showing 7 Northbound", substring = true)
  }

  private companion object {
    /** Times Sq-42 St, which the 7 serves. */
    const val TIMES_SQ_COMPLEX = "611"
  }
}
