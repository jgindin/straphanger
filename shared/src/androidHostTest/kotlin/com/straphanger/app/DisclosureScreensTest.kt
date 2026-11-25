package com.straphanger.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.straphanger.app.ui.permissions.BACKGROUND_LOCATION_DISCLOSURE_TEXT
import com.straphanger.app.ui.permissions.NOTIFICATION_ACCESS_DISCLOSURE_TEXT
import kotlin.test.assertEquals
import org.junit.Test

/** The two permission disclosures: Continue, Not now, system Back and a tab tap. */
class DisclosureScreensTest : AppScreenTest() {
  @Test
  fun backgroundLocationDisclosureNotNowLeavesCaptureOff() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("passive_capture_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("background_location_disclosure").assertIsDisplayed()
    composeRule
      .onNodeWithTag("background_location_disclosure_text")
      .assert(hasText(BACKGROUND_LOCATION_DISCLOSURE_TEXT))

    composeRule.onNodeWithTag("disclosure_not_now").performClick()
    composeRule.onNodeWithTag("background_location_disclosure").assertDoesNotExist()
    composeRule.onNodeWithTag("passive_capture_toggle").performScrollTo().assertIsOff()
    assertEquals(0, passiveCaptureRequests)
  }

  @Test
  fun backgroundLocationDisclosureContinueRequestsCapture() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("passive_capture_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("disclosure_continue").performClick()
    composeRule.onNodeWithTag("background_location_disclosure").assertDoesNotExist()
    assertEquals(1, passiveCaptureRequests)
    assertEquals(0, passiveCaptureDisables)
  }

  @Test
  fun notificationAccessDisclosureNotNowDoesNotOpenSettings() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("notification_access_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("notification_access_disclosure").assertIsDisplayed()
    composeRule
      .onNodeWithTag("notification_access_disclosure_text")
      .assert(hasText(NOTIFICATION_ACCESS_DISCLOSURE_TEXT))
    composeRule.onNodeWithTag("disclosure_not_now").performClick()
    composeRule.onNodeWithTag("notification_access_disclosure").assertDoesNotExist()
    assertEquals(0, notificationAccessRequests)
  }

  @Test
  fun notificationAccessDisclosureContinueOpensSettings() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("notification_access_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("disclosure_continue").performClick()
    composeRule.onNodeWithTag("notification_access_disclosure").assertDoesNotExist()
    assertEquals(1, notificationAccessRequests)
  }

  @Test
  fun tabTapClearsDisclosure() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("notification_access_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("notification_access_disclosure").assertIsDisplayed()
    openTab("MAP")
    composeRule.onNodeWithTag("notification_access_disclosure").assertDoesNotExist()
    composeRule.onNodeWithTag("subway_map_screen").assertIsDisplayed()
  }

  @Test
  fun systemBackDismissesDisclosureLikeNotNow() {
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("passive_capture_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("background_location_disclosure").assertIsDisplayed()

    pressBack()

    composeRule.onNodeWithTag("background_location_disclosure").assertDoesNotExist()
    composeRule.onNodeWithTag("passive_capture_toggle").performScrollTo().assertIsOff()
    assertEquals(0, passiveCaptureRequests)
  }
}
