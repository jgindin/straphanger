package com.straphanger.tools.basemap

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasemapBuilderTest {

  private val fixtureDir =
    File(
      checkNotNull(javaClass.getResource("/mini-basemap")) { "mini-basemap fixture missing" }
        .toURI(),
    )

  private fun build(): Pair<BasemapFile, BasemapReport> =
    BasemapBuilder()
      .build(
        boroughs = GeoJsonReader.read(File(fixtureDir, "boroughs.geojson")),
        parks = GeoJsonReader.read(File(fixtureDir, "parks.geojson")),
        roads = GeoJsonReader.read(File(fixtureDir, "roads.geojson")),
        sourceDate = "2026-11-15",
      )

  /** Undoes [deltaEncode]. */
  private fun decode(encoded: IntArray): List<QPoint> {
    var lng = 0
    var lat = 0
    return (encoded.indices step 2).map { i ->
      lng += encoded[i]
      lat += encoded[i + 1]
      QPoint(lng, lat)
    }
  }

  @Test
  fun boroughsAreSortedByCodeWithHolesKeptAndSpecksDropped() {
    val (basemap, report) = build()
    assertEquals(listOf("Manhattan", "Bronx"), basemap.land.map { it.name })
    val manhattan = basemap.land.first().rings.map(::decode)
    // The outer ring and its hole; the 1 m island is dropped.
    assertEquals(2, manhattan.size)
    // The nearly collinear point on the east edge is simplified away: 4 corners, closed.
    assertEquals(5, manhattan[0].size)
    assertEquals(manhattan[0].first(), manhattan[0].last())
    assertTrue(signedAreaSquareMetres(manhattan[0]) > 0, "outer ring runs anticlockwise")
    assertTrue(signedAreaSquareMetres(manhattan[1]) < 0, "hole runs clockwise")
    assertEquals(QPoint(-7400000, 4070000), manhattan[0].first())
    assertEquals(3, report.landRings)
    assertEquals(1, basemap.version)
    assertEquals(UNITS_PER_DEGREE, basemap.unitsPerDegree)
  }

  @Test
  fun parksAreSortedByPropertyNumberAndParksWithoutGeometryAddNothing() {
    val (basemap, report) = build()
    assertEquals(3, report.parks)
    assertEquals(2, basemap.parks.size)
    assertEquals(QPoint(-7399700, 4070100), decode(basemap.parks[0]).first())
    assertTrue(basemap.parks.all { signedAreaSquareMetres(decode(it)) > 0 })
  }

  @Test
  fun roadsAreClassifiedChainedAndSimplified() {
    val (basemap, _) = build()
    // Pieces 1, 3 and the top-level bridge 2 meet end to end and become one line; the collinear
    // points where they joined are simplified away.
    assertEquals(1, basemap.highways.size)
    assertEquals(
      listOf(QPoint(-7400000, 4072000), QPoint(-7398500, 4072000), QPoint(-7398000, 4072500)),
      decode(basemap.highways.single()),
    )
    // The level-20 street and the level-30 bridge; the unlevelled street and the ramp are left out.
    assertEquals(2, basemap.arterials.size)
  }

  @Test
  fun roadClassRules() {
    assertEquals(RoadClass.HIGHWAY, RoadClass.of("2", null))
    assertEquals(RoadClass.HIGHWAY, RoadClass.of("3", "10"))
    assertEquals(RoadClass.ARTERIAL, RoadClass.of("3", "20"))
    assertEquals(RoadClass.ARTERIAL, RoadClass.of("1", "10"))
    assertNull(RoadClass.of("1", "30"))
    assertNull(RoadClass.of("3", null))
    assertNull(RoadClass.of("4", "10"))
    assertNull(RoadClass.of("9", "10"))
  }

  @Test
  fun buildingTwiceGivesByteIdenticalOutput() {
    assertEquals(encodeBasemap(build().first), encodeBasemap(build().first))
    val dir = Files.createTempDirectory("basemap").toFile()
    try {
      val outputs =
        listOf("a.json", "b.json").map { name ->
          File(dir, name).also { out ->
            main(
              arrayOf(
                "--boroughs",
                File(fixtureDir, "boroughs.geojson").path,
                "--parks",
                File(fixtureDir, "parks.geojson").path,
                "--roads",
                File(fixtureDir, "roads.geojson").path,
                "--out",
                out.path,
                "--source-date",
                "2026-11-15",
              ),
            )
          }
        }
      assertContentEquals(outputs[0].readBytes(), outputs[1].readBytes())
      assertTrue(outputs[0].readText().startsWith("""{"version":1,"sourceDate":"2026-11-15""""))
    } finally {
      dir.deleteRecursively()
    }
  }
}
