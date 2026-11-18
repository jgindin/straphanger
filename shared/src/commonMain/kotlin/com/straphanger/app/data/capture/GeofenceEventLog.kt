package com.straphanger.app.data.capture

import kotlin.time.Clock
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One geofence transition as the platform delivered it, kept to explain how rides were built. */
@Serializable
data class LoggedGeofenceEvent(
  /** `ENTER` or `EXIT`. */
  val transition: String,
  val geofenceId: String,
  /** When the platform says the device was at the location that triggered the transition. */
  val epochMs: Long,
  /** When the app received it; a large gap from [epochMs] means a late fix. */
  val receivedAtEpochMs: Long,
  val lat: Double,
  val lng: Double,
)

/**
 * A rolling record of every geofence transition, station entries and anchor moves alike, unlike
 * [PendingGeofenceEventStore], which drops entries once their ride is saved. Nothing reads it back
 * in the app; it is for diagnosing a ride that was recorded wrongly (pull the file with adb).
 */
interface GeofenceEventLog {
  fun record(event: GeofenceEvent, receivedAtEpochMs: Long)
}

/** Keeps the latest [capacity] transitions as JSON in [file]. */
class FileGeofenceEventLog(
  file: Path,
  fileSystem: FileSystem = SystemFileSystem,
  clock: Clock = Clock.System,
  private val capacity: Int = DEFAULT_CAPACITY,
) : GeofenceEventLog {
  private val stateFile = JsonStateFile(file, fileSystem, clock, LOG_TAG)
  private val serializer = ListSerializer(LoggedGeofenceEvent.serializer())
  private val json = Json { prettyPrint = false }

  override fun record(event: GeofenceEvent, receivedAtEpochMs: Long) {
    val existing = stateFile.read { json.decodeFromString(serializer, it) }.orEmpty()
    val entry =
      LoggedGeofenceEvent(
        transition = if (event is GeofenceEvent.Entered) "ENTER" else "EXIT",
        geofenceId = event.geofenceId,
        epochMs = event.epochMs,
        receivedAtEpochMs = receivedAtEpochMs,
        lat = event.lat,
        lng = event.lng,
      )
    stateFile.write(json.encodeToString(serializer, (existing + entry).takeLast(capacity)))
  }

  companion object {
    const val DEFAULT_CAPACITY = 1000
    private const val LOG_TAG = "StraphangerGeofenceLog"
  }
}
