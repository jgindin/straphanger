package com.straphanger.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Matches a node whose test tag starts with [prefix], e.g. a per-ride tag whose ride ID is random. */
fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
    SemanticsMatcher("test tag starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

fun hasTestTagSuffix(suffix: String): SemanticsMatcher =
    SemanticsMatcher("test tag ends with $suffix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.endsWith(suffix) == true
    }

class SubwayTrackerEmulatorTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /** The subway network loads in the background; tabs show once it has. */
    private fun waitForNetwork() {
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("network_loading").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun mapScreenRendersAndTogglesBetweenMtaColorsAndFrequencyHeatmap() {
        waitForNetwork()
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
        waitForNetwork()
        // 1. Navigate to Rides & Log tab
        composeTestRule.onNodeWithTag("nav_tab_RIDES").performClick()
        composeTestRule.onNodeWithTag("rides_history_screen").assertIsDisplayed()

        // 2. Select 42 St Shuttle preset and log ride
        composeTestRule.onNodeWithTag("preset_42_shuttle").performClick()
        composeTestRule.onNodeWithTag("submit_log_ride_btn").performClick()

        // 3. Simulate a passive GPS ride (14 St-Union Sq -> 86 St) that requires Express vs Local review
        composeTestRule.onNodeWithTag("simulate_passive_ride_btn").performClick()
        composeTestRule.onNodeWithTag("needs_review_banner").assertIsDisplayed()

        // 4. Confirm the first candidate (the express) and verify the review banner clears
        composeTestRule.onNode(hasTestTagPrefix("confirm_candidate_") and hasTestTagSuffix("_0")).performClick()
        composeTestRule.onNodeWithTag("needs_review_banner").assertDoesNotExist()

        // 5. Navigate to Statistics tab and toggle stations vs complexes & Stopped-At vs Boarded/Exited
        composeTestRule.onNodeWithTag("nav_tab_STATS").performClick()
        composeTestRule.onNodeWithTag("stats_dashboard_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stat_card_total_rides").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("stat_card_unique_miles").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("toggle_grouping_STATION_COMPLEXES").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("toggle_visit_BOARDED_OR_EXITED_ONLY").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("toggle_grouping_INDIVIDUAL_STATIONS").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("toggle_visit_STOPPED_AT").performScrollTo().performClick()

        // 6. Navigate to Import & Backup tab and import Timeline.json sample
        composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
        composeTestRule.onNodeWithTag("sources_sync_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_sample_timeline_btn").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("export_backup_btn").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_backup_btn").performScrollTo().assertIsDisplayed()
    }

    private fun headerRideCount(): Int =
        composeTestRule.onNodeWithTag("header_total_rides").onChildAt(0).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)!!.first().text.toInt()

    @Test
    fun rideSurvivesActivityRecreation() {
        waitForNetwork()
        composeTestRule.onNodeWithTag("nav_tab_RIDES").performClick()
        val before = headerRideCount()
        composeTestRule.onNodeWithTag("preset_42_shuttle").performClick()
        composeTestRule.onNodeWithTag("submit_log_ride_btn").performClick()
        assertEquals(before + 1, headerRideCount())

        composeTestRule.activityRule.scenario.recreate()

        composeTestRule.waitForIdle()
        waitForNetwork()
        assertEquals(before + 1, headerRideCount())
    }

    @Test
    fun passiveCaptureShowsDisclosureBeforePermissionPrompt() {
        waitForNetwork()
        composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
        composeTestRule.onNodeWithTag("passive_capture_toggle").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("background_location_disclosure").assertIsDisplayed()
        composeTestRule.onNodeWithTag("background_location_disclosure")
            .assert(hasAnyDescendant(hasText("Straphanger uses your location in the background", substring = true)))

        composeTestRule.onNodeWithTag("disclosure_not_now").performClick()
        composeTestRule.onNodeWithTag("background_location_disclosure").assertDoesNotExist()
        composeTestRule.onNodeWithTag("passive_capture_toggle").performScrollTo().assertIsOff()
    }

    @Test
    fun notificationAccessShowsDisclosureFirst() {
        waitForNetwork()
        composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
        composeTestRule.onNodeWithTag("notification_access_toggle").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("notification_access_disclosure").assertIsDisplayed()
        composeTestRule.onNodeWithTag("notification_access_disclosure")
            .assert(hasAnyDescendant(hasText("Straphanger can read Google Maps transit navigation notifications", substring = true)))
        composeTestRule.onNodeWithTag("disclosure_not_now").performClick()
        composeTestRule.onNodeWithTag("notification_access_disclosure").assertDoesNotExist()
    }

    private fun openAbout() {
        waitForNetwork()
        composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
        composeTestRule.onNodeWithTag("open_about_btn").performScrollTo().performClick()
    }

    @Test
    fun aboutScreenShowsDisclaimerAndDataDate() {
        openAbout()
        composeTestRule.onNodeWithTag("about_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("about_screen")
            .assert(hasAnyDescendant(hasText("Not affiliated with or endorsed by", substring = true)))
        composeTestRule.onNodeWithTag("about_screen")
            .assert(hasAnyDescendant(hasText("MTA GTFS, ", substring = true)))
        composeTestRule.onNodeWithTag("about_screen")
            .assert(hasAnyDescendant(hasText("Version ", substring = true)))
    }

    @Test
    fun aboutScreenListsOpenSourceLicenses() {
        openAbout()
        composeTestRule.onNodeWithTag("about_licenses_btn").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("licenses_screen").assertIsDisplayed()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Apache", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun systemBackDismissesDisclosureLikeNotNow() {
        waitForNetwork()
        composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
        composeTestRule.onNodeWithTag("passive_capture_toggle").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("background_location_disclosure").assertIsDisplayed()

        Espresso.pressBack()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("background_location_disclosure").assertDoesNotExist()
        composeTestRule.onNodeWithTag("straphanger_root").assertIsDisplayed()
        composeTestRule.onNodeWithTag("passive_capture_toggle").performScrollTo().assertIsOff()
    }

    @Test
    fun systemBackClosesLicensesThenAbout() {
        openAbout()
        composeTestRule.onNodeWithTag("about_licenses_btn").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("licenses_screen").assertIsDisplayed()
        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("about_screen").assertIsDisplayed()
        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("about_screen").assertDoesNotExist()
        composeTestRule.onNodeWithTag("straphanger_root").assertIsDisplayed()
    }

    @Test
    fun tabTapClearsDisclosure() {
        waitForNetwork()
        composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
        composeTestRule.onNodeWithTag("notification_access_toggle").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("notification_access_disclosure").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_MAP").performClick()
        composeTestRule.onNodeWithTag("notification_access_disclosure").assertDoesNotExist()
    }
}
