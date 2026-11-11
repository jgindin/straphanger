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
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Exercises kotlinx-serialization decoding/encoding of rides.json against the (minified) app.
 * Each test starts from an empty rides file and manages its own activity.
 */
class RidePersistenceEmulatorTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun clearRides() {
        val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(filesDir, "rides.json").delete()
        File(filesDir, "rides.json.tmp").delete()
    }

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

    private fun headerRideCount(): Int =
        composeTestRule.onNodeWithTag("header_total_rides").onChildAt(0).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)!!.first().text.toInt()

    @Test
    fun importingSampleTimelineAddsRides() {
        launch()
        assertEquals(0, headerRideCount())
        composeTestRule.onNodeWithTag("nav_tab_SOURCES").performClick()
        composeTestRule.onNodeWithTag("import_sample_timeline_btn").performScrollTo().performClick()
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

        // A new activity gets a new ViewModel, which must decode rides.json from disk.
        launch()
        composeTestRule.waitUntil(timeoutMillis = 15_000) { headerRideCount() == 1 }
        assertEquals(1, headerRideCount())
    }

    private companion object {
        const val EXPECTED_SAMPLE_RIDES = 2
    }
}
