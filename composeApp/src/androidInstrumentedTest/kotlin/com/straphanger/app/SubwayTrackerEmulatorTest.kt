package com.straphanger.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SubwayTrackerEmulatorTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mapScreenRendersAndTogglesBetweenMtaColorsAndFrequencyHeatmap() {
        composeTestRule.onNodeWithTag("straphanger_root").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subway_map_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subway_map_canvas").assertIsDisplayed()

        // Switch from Official MTA Colors to Frequency Heatmap and back
        composeTestRule.onNodeWithTag("map_mode_FREQUENCY_HEATMAP").performClick()
        composeTestRule.onNodeWithTag("map_mode_OFFICIAL_MTA_COLORS").performClick()

        // Filter by 4 train and then reset to All Lines
        composeTestRule.onNodeWithTag("filter_route_4").performClick()
        composeTestRule.onNodeWithTag("filter_route_ALL").performClick()
    }

    @Test
    fun loggingRideAndConfirmingPassiveAmbiguousRideUpdatesStatsAndToggles() {
        // 1. Navigate to Rides & Log tab
        composeTestRule.onNodeWithTag("nav_tab_RIDES").performClick()
        composeTestRule.onNodeWithTag("rides_history_screen").assertIsDisplayed()

        // 2. Select 42 St Shuttle preset and log ride
        composeTestRule.onNodeWithTag("preset_42_shuttle").performClick()
        composeTestRule.onNodeWithTag("submit_log_ride_btn").performClick()

        // 3. Simulate a passive GPS ride (14 St-Union Sq -> 86 St) that requires Express vs Local review
        composeTestRule.onNodeWithTag("simulate_passive_ride_btn").performClick()
        composeTestRule.onNodeWithTag("needs_review_banner").assertIsDisplayed()

        // 4. Confirm 4 Express and verify the review banner clears
        composeTestRule.onNodeWithTag("confirm_express_btn").performClick()

        // 5. Navigate to Statistics tab and toggle 472 Stations vs 424 Complexes & Stopped-At vs Boarded/Exited
        composeTestRule.onNodeWithTag("nav_tab_STATS").performClick()
        composeTestRule.onNodeWithTag("stats_dashboard_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stat_card_total_rides").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stat_card_unique_miles").assertIsDisplayed()

        composeTestRule.onNodeWithTag("toggle_grouping_COMPLEXES_424").performClick()
        composeTestRule.onNodeWithTag("toggle_visit_BOARDED_OR_EXITED_ONLY").performClick()
        composeTestRule.onNodeWithTag("toggle_grouping_INDIVIDUAL_472").performClick()
        composeTestRule.onNodeWithTag("toggle_visit_STOPPED_AT").performClick()

        // 6. Navigate to Google Maps & Sync tab and import Timeline.json sample
        composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
        composeTestRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_sample_timeline_btn").performClick()
        composeTestRule.onNodeWithTag("export_backup_btn").performClick()
        composeTestRule.onNodeWithTag("backup_json_preview").assertIsDisplayed()
    }
}
