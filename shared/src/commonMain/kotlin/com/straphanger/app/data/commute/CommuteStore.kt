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

  override fun read(): List<Commute> =
    stateFile.read { Json.decodeFromString(serializer, it) } ?: emptyList()

  override fun write(commutes: List<Commute>) {
    try {
      stateFile.write(if (commutes.isEmpty()) null else Json.encodeToString(serializer, commutes))
    } catch (e: IOException) {
      DiagnosticsLog.warn(COMMUTE_LOG_TAG, "The commutes could not be saved", e)
    }
  }
}
