package com.straphanger.app.data.basemap

import com.straphanger.app.testutil.BundledBasemap
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.map.projectBasemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** The generated nyc_basemap.json that ships in the app. Robolectric, so Compose resources load. */
@RunWith(AndroidJUnit4::class)
class BundledBasemapTest {

  private val basemap = BundledBasemap.data

  @Test
  fun staysUnderTheSizeBudget() {
    val bytes = BundledBasemap.file.length()
    assertTrue(bytes in 100_000..1_500_000, "nyc_basemap.json is $bytes bytes")
  }

  @Test
  fun hasLandForEveryBoroughAndParksAndRoads() {
    assertEquals(
      listOf("Manhattan", "Bronx", "Brooklyn", "Queens", "Staten Island"),
      basemap.land.map { it.name },
    )
    assertTrue(basemap.land.all { it.rings.isNotEmpty() })
    assertTrue(basemap.parks.size > 1_000, "${basemap.parks.size} park rings")
    assertTrue(basemap.highways.size > 100, "${basemap.highways.size} highway lines")
    assertTrue(basemap.arterials.size > 100, "${basemap.arterials.size} arterial lines")
    val rings = basemap.land.flatMap { it.rings } + basemap.parks
    assertTrue(rings.all { it.pointCount >= 4 && it.first() == it.last() }, "rings are closed")
  }

  @Test
  fun fitsTheMapBoundingBox() {
    // The map's projection box is 40.505-40.900 N, 74.260-73.745 W. The five boroughs reach a
    // little beyond it (eastern Queens, Tottenville, the north Bronx), so allow 10% around it.
    val projected = projectBasemap(basemap)
    val all = projected.land + projected.parks + projected.highways + projected.arterials
    val coordinates = all.flatMap { it.asIterable() }
    assertTrue(coordinates.all { it in -0.1f..1.1f }, "basemap reaches outside the map")
    val shapes = basemap.land.flatMap { it.rings } + basemap.parks + basemap.highways
    val lngs = shapes.flatMap { s -> (0 until s.pointCount).map { s.lngLat[2 * it] } }
    val lats = shapes.flatMap { s -> (0 until s.pointCount).map { s.lngLat[2 * it + 1] } }
    assertTrue(lngs.min() > -74.27 && lngs.max() < -73.69, "longitudes ${lngs.min()}..${lngs.max()}")
    assertTrue(lats.min() > 40.49 && lats.max() < 40.92, "latitudes ${lats.min()}..${lats.max()}")
  }

  @Test
  fun nearlyEveryStationIsOnLand() {
    // A check that land and stations line up: only a few stations stand at the water's edge.
    val rings = basemap.land.flatMap { it.rings }
    val stations = BundledNetwork.data.stations
    val onLand = stations.count { st -> rings.count { it.contains(st.lng, st.lat) } % 2 == 1 }
    assertTrue(onLand >= stations.size * 0.97, "$onLand of ${stations.size} stations on land")
  }

  @Test
  fun theBundledResourceLoadsOnceAndMatchesTheFile() = runTest {
    val loaded = NycBasemapCatalog.loadBundled()
    assertSame(loaded, NycBasemapCatalog.loadBundled())
    assertEquals(basemap.sourceDate, loaded.sourceDate)
    assertEquals(basemap.parks.size, loaded.parks.size)
  }

  private fun BasemapShape.first() = lngLat[0] to lngLat[1]

  private fun BasemapShape.last() = lngLat[lngLat.size - 2] to lngLat[lngLat.size - 1]

  /** Even-odd ray casting. */
  private fun BasemapShape.contains(lng: Double, lat: Double): Boolean {
    var inside = false
    var j = pointCount - 1
    for (i in 0 until pointCount) {
      val xi = lngLat[2 * i]
      val yi = lngLat[2 * i + 1]
      val xj = lngLat[2 * j]
      val yj = lngLat[2 * j + 1]
      if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
      j = i
    }
    return inside
  }
}
