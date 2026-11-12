package com.straphanger.tools.gtfs

import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkBuilderTest {

  private val fixtureDir =
    File(checkNotNull(javaClass.getResource("/mini-gtfs")) { "mini-gtfs fixture missing" }.toURI())
  private val feed = GtfsReader.readFeed(fixtureDir)
  private val network =
    NetworkBuilder(
        feed = feed,
        stationListings = GtfsReader.readStationListings(File(fixtureDir, "stations.csv")),
        routeMetadata = RouteMetadataFile.read(File(fixtureDir, "route-metadata.json")),
      )
      .build(sourceDate = "2026-11-10")

  private fun route(id: String) = network.routes.single { it.routeId == id }

  private fun pairKeys() = network.segments.map { setOf(it.fromStationId, it.toStationId) }.toSet()

  @Test
  fun readsSixParentStationsWithComplexAndBoroughFromStationList() {
    assertEquals(6, network.stationCount)
    assertEquals(6, network.complexCount)
    assertEquals(listOf("S1", "S2", "S3", "S4", "S5", "S6"), network.stations.map { it.id })
    val s4 = network.stations.single { it.id == "S4" }
    assertEquals("104", s4.complexId)
    assertEquals(Borough.BROOKLYN, s4.borough)
    assertEquals("2026-11-10", network.sourceDate)
  }

  @Test
  fun expressDaytimePatternSkipsStationsTwoAndFour() {
    val express = route("EXP")
    assertEquals(listOf("S1", "S3", "S5", "S6"), express.stopsAt[ServicePeriod.DAYTIME])
    assertTrue(express.isExpressCapable)
    assertFalse(route("LOC").isExpressCapable)
    assertEquals(
      listOf("S1", "S2", "S3", "S4", "S5", "S6"),
      route("LOC").stopsAt[ServicePeriod.DAYTIME],
    )
  }

  @Test
  fun periodsComeFromDepartureWindows() {
    // Late-night express trips (01:30 and 25:30) stop everywhere.
    assertEquals(
      listOf("S1", "S2", "S3", "S4", "S5", "S6"),
      route("EXP").stopsAt[ServicePeriod.LATE_NIGHT],
    )
    // The local has no late-night trips, so it has no late-night entry.
    assertNull(route("LOC").stopsAt[ServicePeriod.LATE_NIGHT])
    assertEquals(
      listOf("S1", "S2", "S3", "S4", "S5", "S6"),
      route("LOC").stopsAt[ServicePeriod.WEEKEND],
    )
    assertNull(route("EXP").stopsAt[ServicePeriod.WEEKEND])
    assertNull(route("EXP").stopsAt[ServicePeriod.RUSH_PEAK])
  }

  @Test
  fun expressPairExpandsOntoTheLocalSegments() {
    val pairs = pairKeys()
    assertTrue(setOf("S1", "S2") in pairs)
    assertTrue(setOf("S2", "S3") in pairs)
    assertFalse(setOf("S1", "S3") in pairs, "express pair (1,3) must be replaced by (1,2),(2,3)")
    assertFalse(setOf("S3", "S5") in pairs)
    assertEquals(5, network.segments.size)
    for (seg in network.segments) {
      assertEquals("TEST_TRUNK", seg.trunkId)
      assertTrue("LOC" in seg.routes, "${seg.segmentId} carries the local")
      assertTrue("EXP" in seg.routes, "${seg.segmentId} carries the express it runs alongside")
    }
  }

  @Test
  fun systemMilesEqualsTheShapeLength() {
    val shapeMiles =
      feed.shapes.values.sumOf { pts -> pts.zipWithNext().sumOf { (a, b) -> miles(a, b) } }
    assertEquals(shapeMiles, network.systemMiles, 0.01)
    assertEquals(network.systemMiles, route("LOC").totalRouteMiles, 0.01)
  }

  @Test
  fun segmentsAreOrientedAndCarryPolylines() {
    val first =
      network.segments.single { setOf(it.fromStationId, it.toStationId) == setOf("S1", "S2") }
    assertEquals("S1", first.fromStationId)
    assertEquals("S2", first.toStationId)
    assertEquals(40.70, first.polyline.first().lat, 1e-6)
    assertEquals(40.705, first.polyline.last().lat, 1e-6)
  }

  @Test
  fun stationRoutesFollowTheWindows() {
    val s2 = network.stations.single { it.id == "S2" }
    assertEquals(listOf("LOC"), s2.daytimeRoutes)
    assertEquals(listOf("EXP"), s2.lateNightRoutes)
    val s3 = network.stations.single { it.id == "S3" }
    assertEquals(listOf("LOC", "EXP"), s3.daytimeRoutes)
  }

  @Test
  fun simplifiedPolylineKeepsAtMostOnePointPer50Metres() {
    val dense = (0..100).map { GeoPoint(40.70 + it * 0.0001, -73.98) } // ~11 m apart
    val simplified = NetworkBuilder.simplify(dense, minSpacingMeters = 50.0)
    assertEquals(dense.first(), simplified.first())
    assertEquals(dense.last(), simplified.last())
    val lengthMeters = miles(dense.first(), dense.last()) * 1609.344
    assertTrue(
      simplified.size <= lengthMeters / 50.0 + 2,
      "got ${simplified.size} points for $lengthMeters m",
    )
  }

  private fun miles(a: GeoPoint, b: GeoPoint): Double {
    val r = 3958.8
    val dLat = (b.lat - a.lat) * PI / 180
    val dLng = (b.lng - a.lng) * PI / 180
    val h =
      sin(dLat / 2) * sin(dLat / 2) +
        cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * asin(sqrt(h))
  }
}
