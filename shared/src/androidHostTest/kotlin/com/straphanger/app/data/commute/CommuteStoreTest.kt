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
}
