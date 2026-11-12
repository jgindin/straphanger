package com.straphanger.app.data.capture

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

class FilePendingGeofenceEventStoreTest {

  private fun newFile(): Path {
    val dir = Path(SystemTemporaryDirectory, "straphanger-pending-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(dir)
    return Path(dir, "pending_geofence_events.json")
  }

  private val a = GeofenceEvent.Entered("ST_602", 1_000L, 40.73, -73.99)
  private val b = GeofenceEvent.Entered("ST_397", 2_000L, 40.78, -73.96)

  @Test
  fun appendedEventsAreReadBackByANewStore() {
    val file = newFile()
    FilePendingGeofenceEventStore(file).apply {
      append(a)
      append(b)
    }
    assertEquals(listOf(a, b), FilePendingGeofenceEventStore(file).readAll())
    assertFalse(SystemFileSystem.exists(Path(file.toString() + ".tmp")))
  }

  @Test
  fun replacingWithNothingDeletesTheFile() {
    val file = newFile()
    val store = FilePendingGeofenceEventStore(file)
    store.append(a)
    store.replaceAll(emptyList())
    assertFalse(SystemFileSystem.exists(file))
    assertTrue(store.readAll().isEmpty())
  }

  @Test
  fun anUnreadableFileIsTreatedAsEmpty() {
    val file = newFile()
    SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
    val store = FilePendingGeofenceEventStore(file)
    assertTrue(store.readAll().isEmpty())
    store.append(a)
    assertEquals(listOf(a), store.readAll())
  }

  @Test
  fun anUnreadableFileIsMovedAsideWithItsContents() {
    val file = newFile()
    SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
    val clock =
      object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(42L)
      }
    assertTrue(FilePendingGeofenceEventStore(file, clock = clock).readAll().isEmpty())
    val aside = Path(file.parent!!, "pending_geofence_events.unreadable-42.json")
    assertEquals("not json", SystemFileSystem.source(aside).buffered().use { it.readString() })
    assertFalse(SystemFileSystem.exists(file))
  }
}
