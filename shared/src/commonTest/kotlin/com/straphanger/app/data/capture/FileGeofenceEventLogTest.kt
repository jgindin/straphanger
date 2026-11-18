package com.straphanger.app.data.capture

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class FileGeofenceEventLogTest {

  private fun newFile(): Path {
    val dir = Path(SystemTemporaryDirectory, "straphanger-log-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(dir)
    return Path(dir, "geofence_event_log.json")
  }

  private fun read(file: Path): List<LoggedGeofenceEvent> =
    Json.decodeFromString(
      ListSerializer(LoggedGeofenceEvent.serializer()),
      SystemFileSystem.source(file).buffered().use { it.readString() },
    )

  @Test
  fun recordsEntriesAndExitsWithWhenTheyWereReceived() {
    val file = newFile()
    val log = FileGeofenceEventLog(file)

    log.record(GeofenceEvent.Entered("ST_602", 1_000L, 40.73, -73.99), receivedAtEpochMs = 9_000L)
    log.record(GeofenceEvent.Exited("ANCHOR_MACRO_WINDOW", 2_000L, 40.74, -73.98), 2_500L)

    assertEquals(
      listOf(
        LoggedGeofenceEvent("ENTER", "ST_602", 1_000L, 9_000L, 40.73, -73.99),
        LoggedGeofenceEvent("EXIT", "ANCHOR_MACRO_WINDOW", 2_000L, 2_500L, 40.74, -73.98),
      ),
      read(file),
    )
  }

  @Test
  fun keepsOnlyTheLatestEntriesWhenFull() {
    val file = newFile()
    val log = FileGeofenceEventLog(file, capacity = 3)

    repeat(5) { log.record(GeofenceEvent.Entered("ST_$it", it * 1_000L, 0.0, 0.0), it * 1_000L) }

    assertEquals(listOf("ST_2", "ST_3", "ST_4"), read(file).map { it.geofenceId })
  }
}
