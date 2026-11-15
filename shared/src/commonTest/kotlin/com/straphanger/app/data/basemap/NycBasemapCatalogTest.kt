package com.straphanger.app.data.basemap

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NycBasemapCatalogTest {

  private fun file(version: Int = 1, parks: String = "[[-7400000,4070000,100,0,0,100,-100,-100]]") =
    """{"version":$version,"sourceDate":"2026-11-15","unitsPerDegree":100000,
      "land":[{"name":"Manhattan","rings":[[-7400000,4070000,10,0,0,10,-10,-10]]}],
      "parks":$parks,"highways":[[-7399000,4072000,500,0]],"arterials":[]}"""

  @Test
  fun parsesAndUndoesTheDeltaEncoding() {
    val basemap = NycBasemapCatalog.parse(file())
    assertEquals("2026-11-15", basemap.sourceDate)
    assertEquals("Manhattan", basemap.land.single().name)
    assertContentEquals(
      doubleArrayOf(-74.0, 40.7, -73.999, 40.7, -73.999, 40.701, -74.0, 40.7),
      basemap.parks.single().lngLat,
    )
    assertEquals(4, basemap.land.single().rings.single().pointCount)
    assertContentEquals(
      doubleArrayOf(-73.99, 40.72, -73.985, 40.72),
      basemap.highways.single().lngLat,
    )
    assertEquals(0, basemap.arterials.size)
  }

  @Test
  fun rejectsAnUnknownVersionAndOddCoordinateLists() {
    assertFailsWith<IllegalArgumentException> { NycBasemapCatalog.parse(file(version = 2)) }
    assertFailsWith<IllegalArgumentException> {
      NycBasemapCatalog.parse(file(parks = "[[-7400000,4070000,100]]"))
    }
    assertFailsWith<IllegalArgumentException> { NycBasemapCatalog.parse("{ not json") }
  }
}
