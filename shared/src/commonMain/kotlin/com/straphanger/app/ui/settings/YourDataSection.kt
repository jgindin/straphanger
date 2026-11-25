package com.straphanger.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.capture.TimelineImportSummary
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.TIMELINE_EXPORT_HELP_URL
import com.straphanger.app.ui.countOf
import com.straphanger.app.ui.currentPhonePlatform
import com.straphanger.app.ui.timelineImportDescription
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

/** Timeline import, backup and restore. */
@Composable
internal fun YourDataSection(
  uiState: StraphangerUiState,
  backup: BackupCallbacks,
  onImportTimelineFile: (readBytes: suspend () -> ByteArray) -> Unit,
  onBackupMessage: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val timelineLauncher =
    rememberFilePickerLauncher(
      type = FileKitType.File(listOf("json")),
      onError = { failure -> onBackupMessage(filePickerFailedMessage(failure)) },
    ) { file ->
      if (file != null) onImportTimelineFile { file.readBytes() }
    }
  val restoreLauncher =
    rememberFilePickerLauncher(
      type = FileKitType.File(listOf("json")),
      onError = { failure -> onBackupMessage(filePickerFailedMessage(failure)) },
    ) { file ->
      if (file != null) scope.launch { onBackupMessage(BackupFiles(backup).restoreFrom(file)) }
    }
  SettingsSection("Your data") {
    ImportRow(uiState) { timelineLauncher.launch() }
    TimelineHelpLink()
    ActionRow(
      title = "Export backup",
      detail = "Save your rides to a file",
      testTag = "export_backup_btn",
    ) {
      scope.launch { BackupFiles(backup).export()?.let(onBackupMessage) }
    }
    ActionRow(
      title = "Restore from backup",
      detail = "Replace your rides with a backup file",
      testTag = "import_backup_btn",
    ) {
      restoreLauncher.launch()
    }
  }
}

@Composable
private fun ImportRow(uiState: StraphangerUiState, onClick: () -> Unit) {
  val summary = uiState.lastImportSummary
  val importing = uiState.timelineImportInProgress
  val description = timelineImportDescription(currentPhonePlatform)
  ListItem(
    headlineContent = { Text("Import Google Maps Timeline") },
    supportingContent = {
      when {
        importing -> Text("Importing…")
        summary != null -> {
          Column {
            Text(importSummaryText(summary), modifier = Modifier.testTag("timeline_import_summary"))
            Text(description)
          }
        }
        else -> Text(description)
      }
    },
    trailingContent =
      if (importing) {
        {
          CircularProgressIndicator(
            modifier =
              Modifier.size(18.dp).testTag("timeline_import_progress").semantics {
                contentDescription = "Importing"
              },
            strokeWidth = 2.dp,
          )
        }
      } else {
        null
      },
    colors = settingsItemColors(),
    modifier =
      Modifier.testTag("pick_timeline_file_btn").let {
        if (importing) it else it.clickable(role = Role.Button, onClick = onClick)
      },
  )
}

@Composable
private fun ActionRow(title: String, detail: String, testTag: String, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(detail) },
    colors = settingsItemColors(),
    modifier = Modifier.testTag(testTag).clickable(role = Role.Button, onClick = onClick),
  )
}

private fun filePickerFailedMessage(failure: Throwable): String =
  "Could not open the file picker: ${failure.message ?: "unknown error"}"

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
  TextButton(
    onClick = { uriHandler.openUri(TIMELINE_EXPORT_HELP_URL) },
    // ListItem text starts 16 dp in; the button's own 12 dp padding makes up most of that.
    modifier = Modifier.padding(start = 4.dp).testTag("timeline_help_link"),
  ) {
    Text("How do I get my Timeline file?", textDecoration = TextDecoration.Underline)
  }
}
