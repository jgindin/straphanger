package com.straphanger.app.data.local

import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.time.Clock
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/** The rides read at startup, the message to show about them, and whether saving must stop. */
internal data class LoadedRides(
  val rides: List<CompleteRideRecord> = emptyList(),
  val error: String? = null,
  val savesBlocked: Boolean = false,
)

/**
 * Runs [block], a call into a [RidePersistenceBackend], and returns the failure the backend reports
 * (an [IOException] or an [IllegalStateException]) instead of throwing it.
 */
internal inline fun <T> catchingBackendFailure(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: IOException) {
    Result.failure(e)
  } catch (e: IllegalStateException) {
    Result.failure(e)
  }

internal fun Json.decodeRides(raw: String): List<CompleteRideRecord> =
  decodeFromString<List<CompleteRideRecord>>(raw).sortedByDescending { it.ride.startTimeEpochMs }

/**
 * Reads the stored rides at startup. A missing, blank or unreadable snapshot falls back to the copy
 * kept by the previous save; an unreadable one is set aside first so that a later save cannot
 * overwrite it.
 */
internal class RideSnapshotLoader(
  private val backend: RidePersistenceBackend,
  private val json: Json,
  private val clock: Clock,
) {
  private class BackupRead(
    val rides: List<CompleteRideRecord>? = null,
    val failure: Throwable? = null,
  )

  fun load(): LoadedRides = catchingBackendFailure {
    backend.loadSnapshotJson()
  }
    .fold(
      onSuccess = ::loadFrom,
      onFailure = { e ->
        LoadedRides(
          error =
            "Could not read saved rides: ${e.message ?: "unknown error"}. " +
              "Changes will not be saved until the app is restarted.",
          savesBlocked = true,
        )
      },
    )

  private fun loadFrom(raw: String?): LoadedRides {
    val decoded = raw?.takeIf { it.isNotBlank() }?.let { decode(it) }
    val loaded = decoded?.getOrNull()
    if (loaded != null) return LoadedRides(loaded)
    val corruption = decoded?.exceptionOrNull()
    val backup = readBackup()
    return when {
      corruption != null || (raw != null && backup.rides != null) -> setAside(corruption, backup)
      backup.rides != null ->
        // rides.json is missing (a save stopped between its two renames): use the copy.
        LoadedRides(backup.rides, "Saved rides were missing, so the previous copy was restored.")
      backup.failure != null ->
        LoadedRides(
          error =
            "Saved rides were missing and the previous copy could not be read " +
              "(${backup.failure.message ?: "unknown error"}). The app started with no rides."
        )
      else -> LoadedRides()
    }
  }

  private fun decode(raw: String): Result<List<CompleteRideRecord>> =
    try {
      Result.success(json.decodeRides(raw))
    } catch (e: IllegalArgumentException) {
      // Covers SerializationException, which is an IllegalArgumentException.
      Result.failure(e)
    }

  private fun readBackup(): BackupRead {
    val result: Result<List<CompleteRideRecord>?> = catchingBackendFailure {
      backend.loadBackupSnapshotJson()
    }
      .fold(
        onSuccess = { raw ->
          raw?.takeIf { it.isNotBlank() }?.let { decode(it) } ?: Result.success(null)
        },
        onFailure = { Result.failure(it) },
      )
    val failure = result.exceptionOrNull()
    if (failure != null) {
      DiagnosticsLog.warn(
        LOG_TAG,
        "The previous copy of the saved rides could not be read",
        failure,
      )
    }
    return BackupRead(result.getOrNull(), failure)
  }

  /**
   * Sets a blank or unreadable snapshot aside; [backup] holds the previous copy, if it was read.
   */
  private fun setAside(cause: Throwable?, backup: BackupRead): LoadedRides {
    val suffix = "corrupt-${clock.now().toEpochMilliseconds()}"
    val problem = cause?.message ?: if (cause == null) "the file is empty" else "invalid data"
    val keptAs = "The file was kept as a copy named like rides.$suffix.json"
    return catchingBackendFailure { backend.quarantineCorruptSnapshot(suffix) }
      .fold(
        onSuccess = {
          if (backup.rides != null) {
            LoadedRides(
              backup.rides,
              "Saved rides could not be read ($problem). $keptAs and the previous copy of " +
                "your rides was restored.",
            )
          } else {
            LoadedRides(
              error =
                "Saved rides could not be read ($problem). $keptAs and the app started with " +
                  "no rides." +
                  backupFailureNote(backup.failure)
            )
          }
        },
        onFailure = { e ->
          LoadedRides(
            error =
              "Saved rides could not be read and the file could not be set aside " +
                "(${e.message ?: "unknown error"}). " +
                "Changes will not be saved until the app is restarted.",
            savesBlocked = true,
          )
        },
      )
  }

  private fun backupFailureNote(failure: Throwable?): String =
    if (failure == null) "" else " The previous copy could not be read either (${failure.message})."

  private companion object {
    const val LOG_TAG = "StraphangerRides"
  }
}
