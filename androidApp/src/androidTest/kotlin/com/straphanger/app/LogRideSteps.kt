package com.straphanger.app

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput

/** GTFS ids of the 42 St shuttle's platforms. */
private const val TIMES_SQ_SHUTTLE = "902"
private const val GRAND_CENTRAL_SHUTTLE = "901"

/**
 * Logs the 42 St shuttle, Times Sq to Grand Central, the way a rider does: the Log ride button,
 * then each station through the sheet's search, then Log ride. The Rides tab must be showing.
 */
fun ComposeTestRule.logShuttleRide() {
  onNodeWithTag("log_ride_fab").performClick()
  pickLogStation("log_from_field", "Times Sq-42 St", TIMES_SQ_SHUTTLE)
  pickLogStation("log_to_field", "Grand Central-42 St", GRAND_CENTRAL_SHUTTLE)
  onNodeWithTag("submit_log_ride_btn").performClick()
  waitForIdle()
}

private fun ComposeTestRule.pickLogStation(fieldTag: String, name: String, stationId: String) {
  onNodeWithTag(fieldTag).performClick()
  onNodeWithTag("log_station_search_field").performTextInput(name)
  onNodeWithTag("log_station_results").performScrollToKey(stationId)
  onNodeWithTag("log_station_result_$stationId").performClick()
}
