package com.straphanger.app.data.capture

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Station entries recorded by the platform receiver, so entries that arrive while no view model is
 * running are not lost. The receiver appends every entry; [PassiveCaptureCoordinator] replays the
 * entries and keeps only those of the chain it has not yet closed.
 *
 * Calls are synchronous and not thread-safe. On Android the receiver and the coordinator both run
 * on the main thread, so they never overlap.
 */
interface PendingGeofenceEventStore {
  fun readAll(): List<GeofenceEvent.Entered>

  fun append(event: GeofenceEvent.Entered)

  fun replaceAll(events: List<GeofenceEvent.Entered>)
}

class InMemoryPendingGeofenceEventStore : PendingGeofenceEventStore {
  private var events = emptyList<GeofenceEvent.Entered>()

  override fun readAll(): List<GeofenceEvent.Entered> = events

  override fun append(event: GeofenceEvent.Entered) {
    events = events + event
  }

  override fun replaceAll(events: List<GeofenceEvent.Entered>) {
    this.events = events
  }
}

/**
 * Keeps the entries as JSON in [file]. A write goes to `<file>.tmp` and is then moved over [file],
 * as [com.straphanger.app.data.local.FileRidePersistenceBackend] does. An unreadable file is
 * treated as empty.
 */
class FilePendingGeofenceEventStore(
  private val file: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) : PendingGeofenceEventStore {

  @Serializable
  private data class StoredEntry(
    val geofenceId: String,
    val epochMs: Long,
    val lat: Double,
    val lng: Double,
  )

  private val tmpFile = Path(file.toString() + ".tmp")
  private val serializer = ListSerializer(StoredEntry.serializer())
  private val json = Json { ignoreUnknownKeys = true }

  init {
    file.parent?.let { fileSystem.createDirectories(it) }
  }

  override fun readAll(): List<GeofenceEvent.Entered> {
    if (!fileSystem.exists(file)) return emptyList()
    val entries =
      try {
        json.decodeFromString(
          serializer,
          fileSystem.source(file).buffered().use { it.readString() },
        )
      } catch (ignored: Exception) {
        return emptyList()
      }
    return entries.map { GeofenceEvent.Entered(it.geofenceId, it.epochMs, it.lat, it.lng) }
  }

  override fun append(event: GeofenceEvent.Entered) {
    replaceAll(readAll() + event)
  }

  override fun replaceAll(events: List<GeofenceEvent.Entered>) {
    if (events.isEmpty()) {
      if (fileSystem.exists(file)) fileSystem.delete(file)
      return
    }
    val text =
      json.encodeToString(
        serializer,
        events.map { StoredEntry(it.geofenceId, it.epochMs, it.lat, it.lng) },
      )
    fileSystem.sink(tmpFile).buffered().use { it.writeString(text) }
    fileSystem.atomicMove(tmpFile, file)
  }
}
