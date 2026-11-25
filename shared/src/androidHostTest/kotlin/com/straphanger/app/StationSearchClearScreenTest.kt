package com.straphanger.app

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import org.junit.Test

/** The Clear search button in the Status station search and the log sheet's station picker. */
class StationSearchClearScreenTest : AppScreenTest() {
  @Test
  fun theStatusSearchHasAClearButtonOnlyWhenItHasText() {
    launchApp()
    openTab("STATUS")
    composeRule.onNodeWithTag("status_search_clear").assertDoesNotExist()
    composeRule.onNodeWithTag("status_search").performTextInput("Union Sq")
    composeRule.onNodeWithTag("status_search_clear").performClick()
    composeRule.onNodeWithTag("status_search_clear").assertDoesNotExist()
  }

  @Test
  fun theLogStationPickerHasAClearButtonOnlyWhenItHasText() {
    launchApp()
    openTab("RIDES")
    composeRule.onNodeWithTag("log_ride_fab").performClick()
    composeRule.onNodeWithTag("log_from_field").performClick()
    composeRule.onNodeWithTag("log_station_search_clear").assertDoesNotExist()
    composeRule.onNodeWithTag("log_station_search_field").performTextInput("Union Sq")
    composeRule.onNodeWithTag("log_station_search_clear").performClick()
    composeRule.onNodeWithTag("log_station_search_clear").assertDoesNotExist()
    composeRule.onNodeWithTag("log_station_search_field").assertTextEquals("Search stations", "")
  }
}
