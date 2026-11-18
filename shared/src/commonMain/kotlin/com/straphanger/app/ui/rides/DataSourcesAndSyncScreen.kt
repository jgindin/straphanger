package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.capture.TimelineImportSummary
import com.straphanger.app.ui.CardTitle
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.TIMELINE_EXPORT_HELP_URL
import com.straphanger.app.ui.countOf
import com.straphanger.app.ui.currentPhonePlatform
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.timelineImportDescription
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

@Composable
fun DataSourcesAndSyncScreen(
  uiState: StraphangerUiState,
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
        .background(MtaColors.DarkBg)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
        .testTag("sources_sync_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
      text = "Import & Backup",
      color = Color.White,
      fontSize = 20.sp,
      fontWeight = FontWeight.ExtraBold,
    )
    StatusBanner(uiState.statusMessage)
    TimelineImportCard(
      uiState = uiState,
      onImportTimelineFile = onImportTimelineFile,
      onBackupMessage = onBackupMessage,
    )
    PassiveCaptureCard(
      uiState = uiState,
      onRequestPassiveCapture = onRequestPassiveCapture,
      onDisablePassiveCapture = onDisablePassiveCapture,
      onRequestNotificationAccess = onRequestNotificationAccess,
    )
    BackupCard(backup, onBackupMessage)
    Button(
      onClick = onOpenAbout,
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
      modifier = Modifier.testTag("open_about_btn"),
    ) {
      Text("About, data sources and licenses", fontSize = 12.sp)
    }
  }
}

@Composable
private fun StatusBanner(message: String) {
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF162234))
        .border(1.dp, Color(0xFF284268), RoundedCornerShape(10.dp))
        .padding(12.dp)
        .testTag("status_banner"),
  ) {
    Text(
      text = message,
      color = MtaColors.AccentGold,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

/** Google Maps Timeline import, described for this platform only. */
@Composable
private fun TimelineImportCard(
  uiState: StraphangerUiState,
  onImportTimelineFile: (readBytes: suspend () -> ByteArray) -> Unit,
  onBackupMessage: (String) -> Unit,
) {
  val timelineLauncher =
    rememberFilePickerLauncher(
      type = FileKitType.File(listOf("json")),
      onError = { failure -> onBackupMessage(filePickerFailedMessage(failure)) },
    ) { file ->
      if (file != null) onImportTimelineFile { file.readBytes() }
    }
  SectionCard(spacing = 8.dp) {
    CardTitle("1. Google Maps Timeline Import")
    Text(
      text = timelineImportDescription(currentPhonePlatform),
      color = MtaColors.TextSecondary,
      fontSize = 12.sp,
    )
    TimelineHelpLink()
    Button(
      onClick = { timelineLauncher.launch() },
      enabled = !uiState.timelineImportInProgress,
      colors = ButtonDefaults.buttonColors(containerColor = MtaColors.ActionGreen),
      modifier = Modifier.testTag("pick_timeline_file_btn"),
    ) {
      Text("Choose Timeline file…", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    if (uiState.timelineImportInProgress) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("timeline_import_progress"),
      ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("Importing…", color = MtaColors.TextSecondary, fontSize = 12.sp)
      }
    }
    uiState.lastImportSummary?.let { summary ->
      Text(
        text = importSummaryText(summary),
        color = MtaColors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.testTag("timeline_import_summary"),
      )
    }
  }
}

/** Passive station geofences and the Google Maps notification listener. */
@Composable
private fun PassiveCaptureCard(
  uiState: StraphangerUiState,
  onRequestPassiveCapture: () -> Unit,
  onDisablePassiveCapture: () -> Unit,
  onRequestNotificationAccess: () -> Unit,
) {
  SectionCard(spacing = 6.dp) {
    CardTitle("2. Passive Capture")
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
  }
}

/** Backup and restore through files. */
@Composable
private fun BackupCard(backup: BackupCallbacks, onBackupMessage: (String) -> Unit) {
  val scope = rememberCoroutineScope()
  val restoreLauncher =
    rememberFilePickerLauncher(
      type = FileKitType.File(listOf("json")),
      onError = { failure -> onBackupMessage(filePickerFailedMessage(failure)) },
    ) { file ->
      if (file != null) scope.launch { onBackupMessage(BackupFiles(backup).restoreFrom(file)) }
    }
  SectionCard(spacing = 8.dp) {
    CardTitle("3. Backup & Restore")
    Text(
      text =
        "Export saves all rides to a JSON file you choose. Restore reads a file exported by this " +
          "app; rides already on this device are replaced, not duplicated.",
      color = MtaColors.TextSecondary,
      fontSize = 12.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = { scope.launch { BackupFiles(backup).export()?.let(onBackupMessage) } },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
        modifier = Modifier.weight(1f).testTag("export_backup_btn"),
      ) {
        Text("Export backup…", fontSize = 12.sp)
      }
      Button(
        onClick = { restoreLauncher.launch() },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
        modifier = Modifier.weight(1f).testTag("import_backup_btn"),
      ) {
        Text("Restore from backup…", fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun CaptureToggleRow(
  title: String,
  detail: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
      Text(
        text = detail,
        color = MtaColors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.testTag("${testTag}_detail"),
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(testTag),
    )
  }
}

private fun filePickerFailedMessage(failure: Throwable): String =
  "Could not open the file picker: ${failure.message ?: "unknown error"}"

private fun geofenceDetail(uiState: StraphangerUiState): String =
  when {
    uiState.passiveGeofenceEnabled ->
      "${uiState.activeGeofenceCount} regions registered (nearest station complexes and 1 " +
        "window anchor). Rides are recorded when you enter a second station at subway speed."
    uiState.passiveCaptureError != null -> uiState.passiveCaptureError
    else -> "Off"
  }

private fun importSummaryText(summary: TimelineImportSummary): String =
  "Last import: ${countOf(summary.rawSegmentsFound, "subway segment")} found, " +
    "${countOf(summary.ridesImported, "ride")} imported, " +
    "${countOf(summary.multiLegRidesMerged, "transfer")} merged, " +
    "${countOf(summary.duplicatesSkipped, "duplicate")} skipped, " +
    "${summary.unmatchedSegmentsSkipped} not matched to stations"

/** Opens the page that explains how to export a Timeline file from Google Maps. */
@Composable
private fun TimelineHelpLink() {
  val uriHandler = LocalUriHandler.current
  Text(
    "How do I get my Timeline file?",
    color = Color(0xFF8AB4F8),
    fontSize = 13.sp,
    textDecoration = TextDecoration.Underline,
    modifier =
      Modifier.clickable { uriHandler.openUri(TIMELINE_EXPORT_HELP_URL) }
        .padding(vertical = 6.dp)
        .testTag("timeline_help_link"),
  )
}
