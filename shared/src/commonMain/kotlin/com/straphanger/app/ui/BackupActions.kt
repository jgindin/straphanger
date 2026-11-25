package com.straphanger.app.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Exporting rides to a backup file and restoring them, and reporting both in the status banner. */
interface BackupActions {
  /** Returns the backup file contents (JSON) for all rides on this device. */
  suspend fun exportBackup(): String

  /** File name suggested in the save dialog, without extension: straphanger-backup-yyyy-MM-dd. */
  fun suggestedBackupName(): String

  /** Records the outcome of an export in the status banner and returns the message. */
  fun reportBackupExported(fileName: String?): String

  fun reportBackupExportFailed(reason: String?): String

  /**
   * Restores rides from the bytes of a backup file. Rides already present are replaced, not
   * duplicated. Returns a failure when the bytes are not a Straphanger backup; a failure to save
   * the rides is thrown (see [com.straphanger.app.data.local.SubwayDao.replaceRidesOrThrow]).
   */
  suspend fun importBackup(bytes: ByteArray): Result<Int>

  /** Records the outcome of a restore in the status banner and returns the message. */
  fun reportBackupRestored(result: Result<Int>): String

  /**
   * Records a restore that failed while reading or saving rides, as opposed to a file that is not a
   * backup.
   */
  fun reportBackupRestoreFailed(reason: String?): String
}

internal class BackupController(private val session: StraphangerSession) : BackupActions {
  override suspend fun exportBackup(): String = session.backupManager.exportToJson()

  override fun suggestedBackupName(): String {
    val today = session.clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "straphanger-backup-$today"
  }

  override fun reportBackupExported(fileName: String?): String =
    session.setStatus(
      buildString {
        append("Exported ${countOf(session.dao.getAllRides().size, "ride")}")
        if (!fileName.isNullOrBlank()) append(" to $fileName")
      },
    )

  override fun reportBackupExportFailed(reason: String?): String =
    session.setStatus("Backup export failed: ${reason ?: "unknown error"}", failure = true)

  override suspend fun importBackup(bytes: ByteArray): Result<Int> {
    val text =
      try {
        bytes.decodeToString(throwOnInvalidSequence = true)
      } catch (e: CharacterCodingException) {
        return Result.failure(e)
      }
    return session.backupManager.importFromJson(text)
  }

  override fun reportBackupRestored(result: Result<Int>): String =
    session.setStatus(
      result.fold(
        onSuccess = { n -> "Restored ${countOf(n, "ride")} from backup" },
        onFailure = { "Could not restore: the file is not a Straphanger backup" },
      ),
      failure = result.isFailure,
    )

  override fun reportBackupRestoreFailed(reason: String?): String =
    session.setStatus(
      "Could not restore: the rides could not be saved (${reason ?: "unknown error"})",
      failure = true,
    )
}
