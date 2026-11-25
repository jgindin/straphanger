package com.straphanger.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.status.CommuteControls
import com.straphanger.app.ui.theme.Spacing

/**
 * Settings: ride capture, commute alerts, your data and About, each a heading over a group of rows.
 */
@Composable
internal fun SettingsScreen(
  uiState: StraphangerUiState,
  commute: CommuteControls,
  appVersion: String,
  /**
   * Starts a Timeline import that reads the chosen file; it runs in the view model, not this
   * screen.
   */
  onImportTimelineFile: (readBytes: suspend () -> ByteArray) -> Unit,
  suggestedBackupName: String,
  onExportBackup: suspend () -> String,
  onBackupExported: (fileName: String?) -> String,
  onBackupExportFailed: (reason: String?) -> String,
  onBackupRestoreFailed: (reason: String?) -> String,
  onImportBackup: suspend (ByteArray) -> Result<Int>,
  onBackupRestored: (Result<Int>) -> String,
  onBackupMessage: (String) -> Unit,
  onRequestPassiveCapture: () -> Unit = {},
  onDisablePassiveCapture: () -> Unit = {},
  onRequestNotificationAccess: () -> Unit = {},
  onRequestRideNotifications: () -> Unit = {},
  onDisableRideNotifications: () -> Unit = {},
  onOpenNotificationSettings: () -> Unit = {},
  onOpenAbout: () -> Unit = {},
) {
  val backup =
    BackupCallbacks(
      suggestedBackupName,
      onExportBackup,
      onBackupExported,
      onBackupExportFailed,
      onBackupRestoreFailed,
      onImportBackup,
      onBackupRestored,
    )
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState())
        .padding(Spacing.lg)
        .testTag("sources_sync_screen"),
    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
  ) {
    StatusBanner(uiState.statusMessage, failure = uiState.statusIsFailure)
    RideCaptureSection(
      uiState = uiState,
      onRequestPassiveCapture = onRequestPassiveCapture,
      onDisablePassiveCapture = onDisablePassiveCapture,
      onRequestNotificationAccess = onRequestNotificationAccess,
      onRequestRideNotifications = onRequestRideNotifications,
      onDisableRideNotifications = onDisableRideNotifications,
      onOpenNotificationSettings = onOpenNotificationSettings,
    )
    CommuteAlertsSection(commute)
    YourDataSection(uiState, backup, onImportTimelineFile, onBackupMessage)
    AboutSection(appVersion, onOpenAbout)
  }
}

@Composable
private fun AboutSection(appVersion: String, onOpenAbout: () -> Unit) {
  SettingsSection("About") {
    ListItem(
      headlineContent = { Text("About Straphanger") },
      supportingContent = {
        Text(
          if (appVersion.isBlank()) "Data sources and licenses"
          else "Version $appVersion, data sources and licenses",
        )
      },
      colors = settingsItemColors(),
      modifier =
        Modifier.testTag("open_about_btn").clickable(role = Role.Button, onClick = onOpenAbout),
    )
  }
}
