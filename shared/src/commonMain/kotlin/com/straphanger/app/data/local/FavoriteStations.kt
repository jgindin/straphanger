package com.straphanger.app.data.local

import com.straphanger.app.data.capture.JsonStateFile
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.time.Clock
import kotlinx.io.IOException
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The stations the rider starred on the Status tab, as station complex ids in the order they were
 * starred. Calls are synchronous; a store never throws for a storage failure.
 */
interface FavoriteStationsStore {
  fun read(): List<String>

  fun write(complexIds: List<String>)
}

class InMemoryFavoriteStationsStore(private var ids: List<String> = emptyList()) :
  FavoriteStationsStore {
  override fun read(): List<String> = ids

  override fun write(complexIds: List<String>) {
    ids = complexIds
  }
}

/**
 * Keeps the favorites as a JSON list in [file]. An unreadable file is moved aside, logged, and then
 * read as empty; a failed write is logged and the favorites stay as they were in memory only.
 */
class FileFavoriteStationsStore(
  file: Path,
  fileSystem: FileSystem = SystemFileSystem,
  clock: Clock = Clock.System,
) : FavoriteStationsStore {
  private val stateFile = JsonStateFile(file, fileSystem, clock, LOG_TAG)
  private val serializer = ListSerializer(String.serializer())

  override fun read(): List<String> =
    stateFile.read { Json.decodeFromString(serializer, it) } ?: emptyList()

  override fun write(complexIds: List<String>) {
    try {
      stateFile.write(
        if (complexIds.isEmpty()) null else Json.encodeToString(serializer, complexIds),
      )
    } catch (e: IOException) {
      DiagnosticsLog.warn(LOG_TAG, "The favorite stations could not be saved", e)
    }
  }

  private companion object {
    const val LOG_TAG = "StraphangerFavorites"
  }
}
