package com.straphanger.app.data.local

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

class FileFavoriteStationsStoreTest {

  private fun newFile(): Path {
    val dir = Path(SystemTemporaryDirectory, "straphanger-favorites-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(dir)
    return Path(dir, "favorite-stations.json")
  }

  @Test
  fun favoritesAreReadBackInOrderByANewStore() {
    val file = newFile()
    FileFavoriteStationsStore(file).write(listOf("635", "R20"))
    assertEquals(listOf("635", "R20"), FileFavoriteStationsStore(file).read())
  }

  @Test
  fun aMissingFileReadsAsNoFavorites() {
    assertEquals(emptyList(), FileFavoriteStationsStore(newFile()).read())
  }

  @Test
  fun removingTheLastFavoriteDeletesTheFile() {
    val file = newFile()
    val store = FileFavoriteStationsStore(file)
    store.write(listOf("635"))
    store.write(emptyList())
    assertFalse(SystemFileSystem.exists(file))
  }

  @Test
  fun anUnreadableFileIsSetAsideAndReadAsEmpty() {
    val file = newFile()
    SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }

    assertEquals(emptyList(), FileFavoriteStationsStore(file).read())

    assertFalse(SystemFileSystem.exists(file))
    val aside = SystemFileSystem.list(file.parent!!).map { it.name }
    assertTrue(aside.any { it.startsWith("favorite-stations.unreadable-") }, aside.toString())
  }
}
