package com.straphanger.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.status.CommuteAccess
import kotlin.test.assertEquals
import org.junit.Test

/** The Settings page's sections, rows and commute-alert permission status. */
class SettingsScreenTest : AppScreenTest() {
  @Test
  fun sectionHeadingsAppearInOrder() {
    launchApp()
    openSettings()
    val headings =
      composeRule.onAllNodesWithTag("settings_heading").fetchSemanticsNodes().map {
        it.config[SemanticsProperties.Text].single().text
      }
    assertEquals(listOf("Ride capture", "Commute alerts", "Your data", "About"), headings)
  }

  @Test
  fun tappingTheWholeCaptureRowTogglesIt() {
    onRideNotificationsRequested = {
      captureAccess = CaptureAccessState(rideNotificationsTurnedOn = true)
    }
    launchApp()
    openSettings()
    composeRule.onNodeWithTag("ride_notifications_toggle").performScrollTo().assertIsOff()
    // The row's text, not the switch, is what is tapped.
    composeRule
      .onNodeWithTag("ride_notifications_toggle_detail", useUnmergedTree = true)
      .performClick()
    assertEquals(1, rideNotificationRequests)
    composeRule.onNodeWithTag("ride_notifications_toggle").assertIsOn()
  }

  @Test
  fun allowedCommutePermissionsShowNoWarning() {
    launchApp()
    openSettings()
    composeRule
      .onNodeWithTag("settings_commute_notifications")
      .performScrollTo()
      .assert(hasText("Allowed"))
    composeRule.onNodeWithTag("settings_commute_notifications_warning").assertDoesNotExist()
    composeRule.onNodeWithTag("settings_commute_exact_alarms_warning").assertDoesNotExist()
  }

  @Test
  fun blockedCommutePermissionsShowAWarningAndOpenTheirSettings() {
    commuteAccess = CommuteAccess(notificationsAllowed = false, exactAlarmsAllowed = false)
    launchApp()
    openSettings()
    composeRule
      .onNodeWithTag("settings_commute_notifications")
      .performScrollTo()
      .assert(hasText("Blocked — tap to allow in system settings"))
    composeRule.onNodeWithTag("settings_commute_notifications_warning", useUnmergedTree = true)
      .assertExists()
    composeRule
      .onNodeWithTag("settings_commute_exact_alarms")
      .performScrollTo()
      .assert(hasText("Not allowed — alerts may be a few minutes late. Tap to allow."))
    composeRule.onNodeWithTag("settings_commute_exact_alarms_warning", useUnmergedTree = true)
      .assertExists()

    composeRule.onNodeWithTag("settings_commute_notifications").performClick()
    assertEquals(1, notificationSettingsOpens)
    assertEquals(0, exactAlarmSettingsOpens)
    composeRule.onNodeWithTag("settings_commute_exact_alarms").performClick()
    assertEquals(1, exactAlarmSettingsOpens)
  }

  @Test
  fun aboutRowShowsTheVersionAndOpensAbout() {
    launchApp()
    openSettings()
    composeRule
      .onNodeWithTag("open_about_btn")
      .performScrollTo()
      .assertIsDisplayed()
      .assert(hasText("About Straphanger"))
      .assert(hasText("Version 9.8.7, data sources and licenses"))
      .performClick()
    composeRule.onNodeWithTag("about_screen").assertIsDisplayed()
  }
}
