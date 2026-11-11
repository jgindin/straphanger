package com.straphanger.app.data.local

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Stores the ride snapshot in [file]. A save writes `<file>.tmp` and then moves it over [file],
 * so a crash during a save leaves the previous snapshot intact.
 */
class FileRidePersistenceBackend(
    private val file: Path,
    private val fileSystem: FileSystem = SystemFileSystem
) : RidePersistenceBackend {

    private val tmpFile = Path(file.toString() + ".tmp")

    init {
        file.parent?.let { fileSystem.createDirectories(it) }
    }

    override fun loadSnapshotJson(): String? {
        if (!fileSystem.exists(file)) return null
        return fileSystem.source(file).buffered().use { it.readString() }
    }

    override fun saveSnapshotJson(json: String) {
        fileSystem.sink(tmpFile).buffered().use { it.writeString(json) }
        fileSystem.atomicMove(tmpFile, file)
    }

    override fun quarantineCorruptSnapshot(suffix: String) {
        val name = file.name
        val dot = name.lastIndexOf('.')
        val quarantineName = if (dot > 0) {
            name.substring(0, dot) + "." + suffix + name.substring(dot)
        } else {
            "$name.$suffix"
        }
        val target = Path(file.parent ?: Path("."), quarantineName)
        fileSystem.atomicMove(file, target)
    }
}
