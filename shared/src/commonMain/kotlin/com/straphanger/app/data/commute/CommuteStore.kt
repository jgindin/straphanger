package com.straphanger.app.data.commute

import com.straphanger.app.data.capture.JsonStateFile
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.time.Clock
import kotlinx.io.IOException
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The tag for everything the commute code logs. */
internal const val COMMUTE_LOG_TAG = "StraphangerCommutes"

private const val LAST_HOUR = 23

private const val LAST_MINUTE = 59

/** A newer app's extra fields are ignored rather than losing every commute. */
private val json = Json { ignoreUnknownKeys = true }

/** The rider's commutes. Calls are synchronous; a store never throws for a storage failure. */
interface CommuteStore {
  fun read(): List<Commute>

  fun write(commutes: List<Commute>)
}

class InMemoryCommuteStore(private var commutes: List<Commute> = emptyList()) : CommuteStore {
  override fun read(): List<Commute> = commutes

  override fun write(commutes: List<Commute>) {
    this.commutes = commutes
  }
}

/**
 * Keeps the commutes as a JSON list in [file]. An unreadable file is moved aside, logged, and then
 * read as empty; a failed write is logged and the commutes stay as they were in memory only.
 */
class FileCommuteStore(
  file: Path,
  fileSystem: FileSystem = SystemFileSystem,
  clock: Clock = Clock.System,
) : CommuteStore {
  private val stateFile = JsonStateFile(file, fileSystem, clock, COMMUTE_LOG_TAG)
  private val serializer = ListSerializer(Commute.serializer())

  /** Commutes with an impossible time are dropped, so one bad entry cannot break the rest. */
  override fun read(): List<Commute> =
    stateFile
      .read { json.decodeFromString(serializer, it) }
      ?.filter { it.hour in 0..LAST_HOUR && it.minute in 0..LAST_MINUTE }
      ?.map { it.withValidEndTime() }
      .orEmpty()

  /** A half-set or impossible end time reads as no end time; the commute itself stays. */
  private fun Commute.withValidEndTime(): Commute =
    if (endHour in 0..LAST_HOUR && endMinute in 0..LAST_MINUTE) {
      this
    } else {
      copy(endHour = null, endMinute = null)
    }

  override fun write(commutes: List<Commute>) {
    try {
      stateFile.write(if (commutes.isEmpty()) null else json.encodeToString(serializer, commutes))
    } catch (e: IOException) {
      DiagnosticsLog.warn(COMMUTE_LOG_TAG, "The commutes could not be saved", e)
    }
  }
}
