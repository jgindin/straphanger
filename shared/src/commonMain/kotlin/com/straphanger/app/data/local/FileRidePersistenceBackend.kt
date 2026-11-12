package com.straphanger.app.data.local

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Stores the ride snapshot in [file]. A save writes `<file>.tmp`, flushes it and asks [syncFile] to
 * force it to storage, renames the current [file] to `<file>.bak`, and then moves the new file into
 * place. A crash or failed write during a save leaves the previous snapshot in [file] or
 * `<file>.bak`.
 *
 * @param syncFile forces a written file's contents to storage (fsync). kotlinx-io has no portable
 *   call for this, so the platform passes one; the default does nothing beyond the flush.
 */
class FileRidePersistenceBackend(
  private val file: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
  private val syncFile: (Path) -> Unit = {},
) : RidePersistenceBackend {

  private val tmpFile = Path(file.toString() + ".tmp")
  private val backupFile = Path(file.toString() + ".bak")

  init {
    file.parent?.let { fileSystem.createDirectories(it) }
  }

  override fun loadSnapshotJson(): String? = read(file)

  override fun loadBackupSnapshotJson(): String? = read(backupFile)

  private fun read(path: Path): String? {
    if (!fileSystem.exists(path)) return null
    return fileSystem.source(path).buffered().use { it.readString() }
  }

  override fun saveSnapshotJson(json: String) {
    fileSystem.sink(tmpFile).buffered().use {
      it.writeString(json)
      it.flush()
    }
    syncFile(tmpFile)
    if (fileSystem.exists(file)) fileSystem.atomicMove(file, backupFile)
    fileSystem.atomicMove(tmpFile, file)
  }

  override fun quarantineCorruptSnapshot(suffix: String) {
    val name = file.name
    val dot = name.lastIndexOf('.')
    val quarantineName =
      if (dot > 0) {
        name.substring(0, dot) + "." + suffix + name.substring(dot)
      } else {
        "$name.$suffix"
      }
    val target = Path(file.parent ?: Path("."), quarantineName)
    fileSystem.atomicMove(file, target)
  }
}
