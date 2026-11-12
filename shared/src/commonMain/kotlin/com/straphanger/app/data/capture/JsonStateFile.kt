package com.straphanger.app.data.capture

import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.time.Clock
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerializationException

/**
 * A small state file written through `<file>.tmp`, as
 * [com.straphanger.app.data.local.FileRidePersistenceBackend] does. A file that cannot be read or
 * decoded is renamed to `<name>.unreadable-<epochMs>.<ext>` beside it and reported to
 * [DiagnosticsLog], so the next write does not overwrite what it held.
 */
internal class JsonStateFile(
  private val file: Path,
  private val fileSystem: FileSystem,
  private val clock: Clock,
  private val logTag: String,
) {
  private val tmpFile = Path(file.toString() + ".tmp")

  init {
    file.parent?.let { fileSystem.createDirectories(it) }
  }

  /** The decoded contents, or null when the file is missing or was set aside as unreadable. */
  fun <T> read(decode: (String) -> T): T? =
    if (fileSystem.exists(file)) readExisting(decode) else null

  /** Replaces the contents with [text], or deletes the file when [text] is null. */
  fun write(text: String?) {
    when {
      text != null -> {
        fileSystem.sink(tmpFile).buffered().use { it.writeString(text) }
        fileSystem.atomicMove(tmpFile, file)
      }
      fileSystem.exists(file) -> fileSystem.delete(file)
    }
  }

  private fun <T> readExisting(decode: (String) -> T): T? =
    try {
      decode(fileSystem.source(file).buffered().use { it.readString() })
    } catch (e: IOException) {
      setAside(e)
      null
    } catch (e: SerializationException) {
      setAside(e)
      null
    } catch (e: IllegalArgumentException) {
      setAside(e)
      null
    }

  private fun setAside(cause: Exception) {
    val target = Path(file.parent ?: Path("."), unreadableName(file.name, clock))
    try {
      fileSystem.atomicMove(file, target)
      DiagnosticsLog.warn(logTag, "$file could not be read; moved it to $target", cause)
    } catch (e: IOException) {
      DiagnosticsLog.warn(logTag, "$file could not be read", cause)
      DiagnosticsLog.warn(logTag, "$file could not be moved aside; the next write replaces it", e)
    }
  }
}

private fun unreadableName(name: String, clock: Clock): String {
  val suffix = "unreadable-${clock.now().toEpochMilliseconds()}"
  val dot = name.lastIndexOf('.')
  return if (dot > 0) "${name.substring(0, dot)}.$suffix${name.substring(dot)}" else "$name.$suffix"
}
