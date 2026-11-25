package com.straphanger.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.straphanger.app.ui.status.CommuteControls
import com.straphanger.app.ui.theme.MtaColors
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_warning

/** Whether commute alerts can reach the rider: notifications and exact alarms. */
@Composable
internal fun CommuteAlertsSection(commute: CommuteControls) {
  SettingsSection("Commute alerts") {
    PermissionRow(
      title = "Notifications",
      allowed = commute.access.notificationsAllowed,
      allowedText = "Allowed",
      blockedText = "Blocked — tap to allow in system settings",
      onFix = commute.onOpenNotificationSettings,
      testTag = "settings_commute_notifications",
    )
    PermissionRow(
      title = "Exact alarms",
      allowed = commute.access.exactAlarmsAllowed,
      allowedText = "Allowed — alerts come on the minute",
      blockedText = "Not allowed — alerts may be a few minutes late. Tap to allow.",
      onFix = commute.onOpenExactAlarmSettings,
      testTag = "settings_commute_exact_alarms",
    )
  }
}

/** A permission's status; tapping it opens the system screen that fixes it, when it needs that. */
@Composable
private fun PermissionRow(
  title: String,
  allowed: Boolean,
  allowedText: String,
  blockedText: String,
  onFix: () -> Unit,
  testTag: String,
) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(if (allowed) allowedText else blockedText) },
    leadingContent =
      if (allowed) {
        null
      } else {
        {
          Icon(
            painterResource(Res.drawable.ic_warning),
            contentDescription = "Needs attention",
            tint = MtaColors.Warning,
            modifier = Modifier.testTag("${testTag}_warning"),
          )
        }
      },
    colors = settingsItemColors(),
    modifier =
      Modifier.testTag(testTag).let {
        if (allowed) it else it.clickable(role = Role.Button, onClick = onFix)
      },
  )
}
