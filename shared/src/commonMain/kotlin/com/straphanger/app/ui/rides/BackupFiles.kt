package com.straphanger.app.ui.rides

import com.straphanger.app.ui.catchingFileFailure
import com.straphanger.app.ui.catchingSaveFailure
import com.straphanger.app.ui.reason
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.writeString

/** What the backup card needs from the view model; see [BackupFiles]. */
internal class BackupCallbacks(
  val suggestedBackupName: String,
  val onExportBackup: suspend () -> String,
  val onBackupExported: (fileName: String?) -> String,
  val onBackupExportFailed: (reason: String?) -> String,
  val onBackupRestoreFailed: (reason: String?) -> String,
  val onImportBackup: suspend (ByteArray) -> Result<Int>,
  val onBackupRestored: (Result<Int>) -> String,
)

/** Writes and reads backup files the user chooses; each step returns the message to show. */
internal class BackupFiles(private val backup: BackupCallbacks) {

  /**
   * Asks for a file and writes the backup to it. Returns null when the user cancelled the save
   * dialog.
   */
  suspend fun export(): String? = catchingFileFailure {
    FileKit.openFileSaver(
        suggestedName = backup.suggestedBackupName,
        defaultExtension = "json",
      )
      ?.let { file ->
        file.writeString(backup.onExportBackup())
        backup.onBackupExported(file.name)
      }
  }
    .getOrElse { e -> backup.onBackupExportFailed(e.message) }

  /**
   * Restores the rides in [file]. A file that cannot be read, is not a backup, or whose rides
   * cannot be saved is reported in the returned message.
   */
  suspend fun restoreFrom(file: PlatformFile): String = catchingFileFailure {
    file.readBytes()
  }
    .fold(
      onSuccess = { bytes -> restore(bytes) },
      onFailure = { e -> "Could not read the file: ${e.reason}" },
    )

  private suspend fun restore(bytes: ByteArray): String = catchingSaveFailure {
    backup.onBackupRestored(backup.onImportBackup(bytes))
  }
    .getOrElse { e -> backup.onBackupRestoreFailed(e.message) }
}
