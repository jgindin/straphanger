package com.straphanger.app.ui.map

import com.straphanger.app.data.basemap.BasemapArea
import com.straphanger.app.data.basemap.BasemapShape
import com.straphanger.app.data.basemap.NycBasemap
import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

class BasemapProjectionTest {

  @Test
  fun basemapUsesTheStationProjectionWithoutClamping() {
    val station = TestNetwork.data.stations.first()
    val basemap =
      NycBasemap(
        sourceDate = "2026-11-15",
        land = listOf(BasemapArea("Queens", listOf(BasemapShape(doubleArrayOf(-73.70, 40.75))))),
        parks = listOf(BasemapShape(doubleArrayOf(station.lng, station.lat))),
        highways = emptyList(),
        arterials = listOf(BasemapShape(doubleArrayOf(-74.26, 40.90, -73.745, 40.505))),
      )
    val projected = projectBasemap(basemap)
    val projectedStation = projectGeometry(TestNetwork.data).stations.first()
    assertEquals(projectedStation.normX, projected.parks.single()[0], 1e-6f)
    assertEquals(projectedStation.normY, projected.parks.single()[1], 1e-6f)
    // The box's corners are 0 and 1; eastern Queens lies beyond the east edge and is not clamped.
    projected.arterials.single().zip(listOf(0f, 0f, 1f, 1f)).forEach { (actual, expected) ->
      assertEquals(expected, actual, 1e-5f)
    }
    assertEquals(1.087f, projected.land.single()[0], 1e-3f)
    assertEquals("2026-11-15", projected.sourceDate)
  }
}
