package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.Direction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.datetime.DayOfWeek
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

class CommuteStoreTest {
  private fun newFile(): Path {
    val dir = Path(SystemTemporaryDirectory, "straphanger-commutes-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(dir)
    return Path(dir, "commutes.json")
  }

  private val commute =
    Commute("c_1", "6", "635", Direction.NORTH, setOf(DayOfWeek.MONDAY), 7, 45, enabled = false)

  @Test
  fun roundTripsThroughTheFile() {
    val file = newFile()
    FileCommuteStore(file).write(listOf(commute))
    assertEquals(listOf(commute), FileCommuteStore(file).read())
  }

  @Test
  fun corruptFileReadsAsEmpty() {
    val file = newFile()
    SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
    assertEquals(emptyList(), FileCommuteStore(file).read())
  }

  @Test
  fun emptyListDeletesTheFile() {
    val file = newFile()
    val store = FileCommuteStore(file)
    store.write(listOf(commute))
    store.write(emptyList())
    assertFalse(SystemFileSystem.exists(file))
  }

  @Test
  fun dropsEntriesWithAnImpossibleTime() {
    val file = newFile()
    val bad =
      listOf(commute.copy(id = "h", hour = 24), commute.copy(id = "m", minute = -1), commute)
    FileCommuteStore(file).write(bad)
    assertEquals(listOf(commute), FileCommuteStore(file).read())
  }

  @Test
  fun ignoresUnknownFields() {
    val file = newFile()
    val json =
      """[{"id":"c_1","routeId":"6","complexId":"635","direction":"NORTH","days":["MONDAY"],""" +
        """"hour":7,"minute":45,"enabled":false,"addedLater":"x"}]"""
    SystemFileSystem.sink(file).buffered().use { it.writeString(json) }
    assertEquals(listOf(commute), FileCommuteStore(file).read())
  }

  private fun readJson(json: String): List<Commute> {
    val file = newFile()
    SystemFileSystem.sink(file).buffered().use { it.writeString(json) }
    return FileCommuteStore(file).read()
  }

  private val head =
    """{"id":"c_1","routeId":"6","complexId":"635","direction":"NORTH","days":["MONDAY"],""" +
      """"hour":7,"minute":45,"enabled":false"""

  @Test
  fun aZeroTwentyFiveFileReadsWithNoEndTime() {
    assertEquals(listOf(commute), readJson("[$head}]"))
  }

  @Test
  fun anEndTimeRoundTrips() {
    val file = newFile()
    val withEnd = commute.copy(endHour = 8, endMinute = 30)
    FileCommuteStore(file).write(listOf(withEnd))
    assertEquals(listOf(withEnd), FileCommuteStore(file).read())
  }

  @Test
  fun aBadEndTimeIsDroppedButTheCommuteKept() {
    assertEquals(listOf(commute), readJson("[$head,\"endHour\":25,\"endMinute\":0}]"))
    assertEquals(listOf(commute), readJson("[$head,\"endHour\":8}]"))
    assertEquals(listOf(commute), readJson("[$head,\"endHour\":8,\"endMinute\":60}]"))
  }
}
