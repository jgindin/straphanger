package com.straphanger.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.RideTapInbox
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Test

/** The ride-recorded notification: its switch on Import & Backup, and opening a tapped ride. */
class RideNotificationScreenTest : AppScreenTest() {
  @After
  fun clearInbox() {
    RideTapInbox.consume()
  }

  @Test
  fun rideNotificationSwitchCallsRequestWhenTurnedOnAndDisableWhenOff() {
    launchApp()
    openTab("SOURCES")
    val toggle = composeRule.onNodeWithTag("ride_notifications_toggle").performScrollTo()
    toggle.assertIsOff()
    composeRule
      .onNodeWithTag("ride_notifications_toggle_detail", useUnmergedTree = true)
      .assertTextEquals("Off.")

    toggle.performClick()
    assertEquals(1, rideNotificationRequests)
    assertEquals(0, rideNotificationDisables)

    composeRule.runOnUiThread {
      captureAccess = CaptureAccessState(rideNotificationsTurnedOn = true)
    }
    val on = composeRule.onNodeWithTag("ride_notifications_toggle").performScrollTo()
    on.assertIsOn()
    composeRule
      .onNodeWithTag("ride_notifications_toggle_detail", useUnmergedTree = true)
      .assertTextEquals(
        "A notification appears when passive capture or Google Maps records a ride.",
      )
    on.performClick()
    assertEquals(1, rideNotificationDisables)
  }

  @Test
  fun rideNotificationSwitchExplainsWhenBlocked() {
    captureAccess =
      CaptureAccessState(rideNotificationsTurnedOn = true, postNotificationsGranted = false)
    launchApp()
    openTab("SOURCES")
    composeRule.onNodeWithTag("ride_notifications_toggle").performScrollTo().assertIsOff()
    composeRule
      .onNodeWithTag("ride_notifications_toggle_detail", useUnmergedTree = true)
      .assertTextEquals(
        "Notifications are blocked for Straphanger. Tap to allow them in system settings.",
      )
  }

  @Test
  fun tappingTheBlockedSwitchOpensNotificationSettings() {
    captureAccess =
      CaptureAccessState(rideNotificationsTurnedOn = true, postNotificationsGranted = false)
    launchApp()
    openTab("SOURCES")
    composeRule.onNodeWithTag("ride_notifications_toggle").performScrollTo().performClick()
    assertEquals(1, notificationSettingsOpens)
    assertEquals(0, rideNotificationRequests)
    assertEquals(0, rideNotificationDisables)
  }

  @Test
  fun tappedRideOpensRidesTabHighlighted() {
    val vm = launchApp()
    composeRule.runOnUiThread { vm.loadDemoRides() }
    // The last row is far down the list, so showing it means the list scrolled to it.
    val rideId = vm.uiState.value.rides.last().ride.rideId

    composeRule.runOnUiThread {
      vm.showRidesOnRoute("NOT_A_LINE")
      RideTapInbox.open(rideId)
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("rides_history_screen").assertIsDisplayed()
    composeRule.onNodeWithTag("ride_item_$rideId").assertIsDisplayed()
    composeRule.onNodeWithTag("ride_row_highlighted").assertIsDisplayed()
    assertEquals(null, vm.uiState.value.ridesRouteFilter)

    openTab("STATS")
    openTab("RIDES")
    composeRule.onNodeWithTag("ride_row_highlighted").assertDoesNotExist()
  }
}
