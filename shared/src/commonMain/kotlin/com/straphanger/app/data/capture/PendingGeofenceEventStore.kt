package com.straphanger.app.data.capture

import kotlin.time.Clock
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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
 * as [com.straphanger.app.data.local.FileRidePersistenceBackend] does. An unreadable file is moved
 * aside, logged, and then treated as empty, so its entries can still be recovered by hand.
 */
class FilePendingGeofenceEventStore(
  file: Path,
  fileSystem: FileSystem = SystemFileSystem,
  clock: Clock = Clock.System,
) : PendingGeofenceEventStore {

  @Serializable
  private data class StoredEntry(
    val geofenceId: String,
    val epochMs: Long,
    val lat: Double,
    val lng: Double,
  )

  private val stateFile = JsonStateFile(file, fileSystem, clock, LOG_TAG)
  private val serializer = ListSerializer(StoredEntry.serializer())
  private val json = Json { ignoreUnknownKeys = true }

  override fun readAll(): List<GeofenceEvent.Entered> =
    stateFile
      .read { json.decodeFromString(serializer, it) }
      .orEmpty()
      .map { GeofenceEvent.Entered(it.geofenceId, it.epochMs, it.lat, it.lng) }

  override fun append(event: GeofenceEvent.Entered) {
    replaceAll(readAll() + event)
  }

  override fun replaceAll(events: List<GeofenceEvent.Entered>) {
    val text =
      events
        .takeIf { it.isNotEmpty() }
        ?.let { kept ->
          json.encodeToString(
            serializer,
            kept.map { StoredEntry(it.geofenceId, it.epochMs, it.lat, it.lng) },
          )
        }
    stateFile.write(text)
  }

  private companion object {
    const val LOG_TAG = "StraphangerPendingEvents"
  }
}
