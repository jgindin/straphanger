package com.straphanger.app.data.widget

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

class CommuteWidgetStoreTest {
  private fun newFile(): Path {
    val dir = Path(SystemTemporaryDirectory, "straphanger-widgets-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(dir)
    return Path(dir, "commute_widgets.json")
  }

  @Test
  fun roundTripsThroughTheFile() {
    val file = newFile()
    val store = FileCommuteWidgetStore(file)
    store.setWidget(7, listOf("c1", "c2"))
    store.setWidget(9, listOf("c3"))
    store.setTimes("c1", CommuteTimes("3 min", "Delays", 100L, 200L))
    store.removeWidgets(listOf(9))
    val reread = FileCommuteWidgetStore(file)
    assertEquals(mapOf(7 to listOf("c1", "c2")), reread.widgets())
    assertEquals(mapOf("c1" to CommuteTimes("3 min", "Delays", 100L, 200L)), reread.times())
  }

  @Test
  fun corruptFileReadsAsEmpty() {
    val file = newFile()
    SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
    val store = FileCommuteWidgetStore(file)
    assertEquals(emptyMap(), store.widgets())
    assertEquals(emptyMap(), store.times())
    // Unknown fields from a newer app are ignored.
    SystemFileSystem.sink(file).buffered().use {
      it.writeString("""{"widgets":{"4":["c1"]},"addedLater":1}""")
    }
    assertEquals(mapOf(4 to listOf("c1")), store.widgets())
  }
}
