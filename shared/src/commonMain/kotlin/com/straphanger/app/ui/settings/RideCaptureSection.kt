package com.straphanger.app.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.straphanger.app.ui.StraphangerUiState

/** Passive station geofences, the Google Maps notification listener and ride notifications. */
@Composable
internal fun RideCaptureSection(
  uiState: StraphangerUiState,
  onRequestPassiveCapture: () -> Unit,
  onDisablePassiveCapture: () -> Unit,
  onRequestNotificationAccess: () -> Unit,
  onRequestRideNotifications: () -> Unit,
  onDisableRideNotifications: () -> Unit,
  onOpenNotificationSettings: () -> Unit,
) {
  SettingsSection("Ride capture") {
    CaptureToggleRow(
      title = "Station geofences",
      detail = geofenceDetail(uiState),
      checked = uiState.passiveGeofenceEnabled,
      onCheckedChange = { on -> if (on) onRequestPassiveCapture() else onDisablePassiveCapture() },
      testTag = "passive_capture_toggle",
    )
    CaptureToggleRow(
      title = "Google Maps notifications",
      detail =
        if (uiState.googleMapsNotificationEnabled) {
          "Notification access granted. Rides are read from Google Maps transit navigation."
        } else {
          "Notification access is off. Turning this on opens system settings."
        },
      checked = uiState.googleMapsNotificationEnabled,
      onCheckedChange = { onRequestNotificationAccess() },
      testTag = "notification_access_toggle",
    )
    CaptureToggleRow(
      title = "Notify me when a ride is recorded",
      detail = rideNotificationsDetail(uiState),
      checked = uiState.rideNotificationsTurnedOn && uiState.postNotificationsGranted,
      onCheckedChange = { on ->
        when {
          uiState.rideNotificationsTurnedOn && !uiState.postNotificationsGranted ->
            onOpenNotificationSettings()
          on -> onRequestRideNotifications()
          else -> onDisableRideNotifications()
        }
      },
      testTag = "ride_notifications_toggle",
    )
  }
}

/** The whole row is the switch, so there is one target. */
@Composable
private fun CaptureToggleRow(
  title: String,
  detail: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String,
) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(detail, modifier = Modifier.testTag("${testTag}_detail")) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    colors = settingsItemColors(),
    modifier =
      Modifier.testTag(testTag)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
  )
}

private fun rideNotificationsDetail(uiState: StraphangerUiState): String =
  when {
    !uiState.rideNotificationsTurnedOn -> "Off."
    !uiState.postNotificationsGranted ->
      "Notifications are blocked for Straphanger. Tap to allow them in system settings."
    else -> "A notification appears when passive capture or Google Maps records a ride."
  }

private fun geofenceDetail(uiState: StraphangerUiState): String =
  when {
    uiState.passiveGeofenceEnabled ->
      "${uiState.activeGeofenceCount} regions registered (nearest station complexes and 1 " +
        "window anchor). Rides are recorded when you enter a second station at subway speed."
    uiState.passiveCaptureError != null -> uiState.passiveCaptureError
    else -> "Off"
  }
