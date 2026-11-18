package com.straphanger.tools.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An express on one trunk group can skip stops whose local service belongs to another trunk group
 * (the B on the Brighton line, where the Q is the local). Its express pair must still be laid onto
 * the local segments instead of becoming a parallel segment.
 */
class CrossTrunkExpansionTest {

  private fun stop(id: String, lat: Double) =
    listOf(
      GtfsStop(id, "Stop $id", lat, -73.96, 1, null),
      GtfsStop("${id}N", "Stop $id", lat, -73.96, 0, id),
    )

  private val feed =
    GtfsFeed(
      stops = stop("P1", 40.650) + stop("P2", 40.655) + stop("P3", 40.660),
      routes =
        listOf(
          GtfsRoute("Q", "Q", "Local", "F6BC26", "000000", 1),
          GtfsRoute("B", "B", "Express", "EB6800", "FFFFFF", 2),
        ),
      trips =
        listOf(
          GtfsTrip("q1", "Q", "Weekday", 0, null),
          GtfsTrip("b1", "B", "Weekday", 0, null),
        ),
      calendars =
        listOf(GtfsCalendar("Weekday", listOf(true, true, true, true, true, false, false))),
      tripStops =
        listOf(
          GtfsTripStops("q1", listOf("P1N", "P2N", "P3N"), 9 * 3600),
          GtfsTripStops("b1", listOf("P1N", "P3N"), 9 * 3600),
        ),
      shapes = emptyMap(),
    )

  private val network =
    NetworkBuilder(
        feed = feed,
        stationListings =
          listOf("P1", "P2", "P3").map { StationListing(it, it, Borough.BROOKLYN, "Stop $it") },
        routeMetadata =
          mapOf(
            "Q" to RouteMetadata("BMT_BROADWAY", "BMT", "Local"),
            "B" to RouteMetadata("IND_6AV", "IND", "Express"),
          ),
      )
      .build("2026-11-10")

  @Test
  fun expressPairFromAnotherTrunkIsLaidOntoTheLocalSegments() {
    assertEquals(
      setOf(setOf("P1", "P2"), setOf("P2", "P3")),
      network.segments.map { setOf(it.fromStationId, it.toStationId) }.toSet(),
    )
    for (seg in network.segments) {
      assertEquals(listOf("Q", "B"), seg.routes)
      assertEquals("BMT_BROADWAY", seg.trunkId, "the trunk that runs the local keeps the segment")
    }
    assertTrue(network.routes.single { it.routeId == "B" }.isExpressCapable)
  }

  @Test
  fun missingShapeFallsBackToHaversineTimes108() {
    val seg = network.segments.first()
    val straight = NetworkBuilder.haversineMiles(40.650, -73.96, 40.655, -73.96)
    assertEquals(straight * 1.08, seg.distanceMiles, 0.0001)
  }

  @Test
  fun sharedLocalTrackOnTwoTrunksIsOneSegmentAndNotAnExpress() {
    // The B and C both run local on Central Park West but belong to different trunk groups.
    val shared =
      NetworkBuilder(
          feed =
            feed.copy(
              tripStops =
                listOf(
                  GtfsTripStops("q1", listOf("P1N", "P2N", "P3N"), 9 * 3600),
                  GtfsTripStops("b1", listOf("P1N", "P2N", "P3N"), 9 * 3600),
                ),
            ),
          stationListings =
            listOf("P1", "P2", "P3").map { StationListing(it, it, Borough.MANHATTAN, "Stop $it") },
          routeMetadata =
            mapOf(
              "Q" to RouteMetadata("IND_8AV", "IND", "Local"),
              "B" to RouteMetadata("IND_6AV", "IND", "Local"),
            ),
        )
        .build("2026-11-10")
    assertEquals(2, shared.segments.size)
    shared.segments.forEach { assertEquals(listOf("Q", "B"), it.routes) }
    shared.routes.forEach { assertEquals(false, it.isExpressCapable, it.routeId) }
  }
}
