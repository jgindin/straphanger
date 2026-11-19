package com.straphanger.app.data.realtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RealtimeFeedsTest {
  private fun resource(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/realtime/$name")).readBytes()

  private val lFeed = decodeFeed(resource("l-feed.pb"))
  private val alertsFeed = decodeFeed(resource("alerts-feed.pb"))

  @Test
  fun theRealLFeedYieldsArrivalsAtLStations() {
    val board = ArrivalBoard.from(listOf(lFeed))
    assertTrue(board.size > 50, "arrivals: ${board.size}")
    val now = lFeed.header.timestamp
    val atBedford = board.arrivalsAt("L08", now - 3_600, perLine = 100)
    assertTrue(atBedford.isNotEmpty())
    assertTrue(atBedford.all { it.routeId == "L" && it.stationId == "L08" })
    assertEquals(setOf(Direction.NORTH, Direction.SOUTH), atBedford.map { it.direction }.toSet())
  }

  @Test
  fun arrivalsAreSoonestFirstLimitedPerLineAndDirectionAndDropPastTrains() {
    val t = 10_000L
    fun a(route: String, dir: Direction, at: Long) = Arrival(route, "X", dir, at)
    val board =
      ArrivalBoard(
        listOf(
          a("A", Direction.NORTH, t + 600),
          a("A", Direction.NORTH, t + 60),
          a("A", Direction.NORTH, t + 300),
          a("A", Direction.NORTH, t + 900),
          a("A", Direction.SOUTH, t + 120),
          a("A", Direction.SOUTH, t - 400),
          a("C", Direction.NORTH, t - 10),
        ),
      )
    val shown = board.arrivalsAt("X", t, perLine = 3)
    assertEquals(listOf(t - 10, t + 60, t + 120, t + 300, t + 600), shown.map { it.epochSeconds })
    assertEquals(0, shown.first().minutesFrom(t))
    assertEquals(1, shown[1].minutesFrom(t))
    assertTrue(board.arrivalsAt("elsewhere", t).isEmpty())
  }

  @Test
  fun realAlertsAreReadWithTheirLinesAndKindAndOnlyWhileActive() {
    // The first two alerts in the sample are delays running 1795052967..1795054500; the planned
    // work in it starts later.
    val duringDelays = activeAlerts(alertsFeed, 1_795_053_000)
    assertEquals(listOf(AlertKind.DELAY, AlertKind.DELAY), duringDelays.map { it.kind })
    assertEquals(setOf("N", "W"), duringDelays[0].routes)
    assertEquals(setOf("D"), duringDelays[1].routes)
    assertTrue(duringDelays.all { it.header.isNotEmpty() })

    val laterOn = activeAlerts(alertsFeed, 1_795_270_000)
    assertEquals(listOf(AlertKind.PLANNED, AlertKind.PLANNED), laterOn.map { it.kind })
    assertEquals(setOf(setOf("SI"), setOf("J")), laterOn.map { it.routes }.toSet())

    assertTrue(activeAlerts(alertsFeed, 1_700_000_000).isEmpty())
  }

  @Test
  fun feedsAreChosenForTheLinesAsked() {
    assertEquals(setOf(RealtimeFeed.NUMBERED, RealtimeFeed.L), RealtimeFeed.forRoutes(listOf("4", "L", "7")))
    assertEquals(setOf(RealtimeFeed.ACE), RealtimeFeed.forRoutes(listOf("A", "C", "E")))
    assertTrue(RealtimeFeed.forRoutes(listOf("nonsense")).isEmpty())
  }

  private fun client(handler: (String) -> ByteArray?): RealtimeFeedClient =
    RealtimeFeedClient(
      http =
        HttpClient(
          MockEngine { request ->
            val bytes = handler(request.url.encodedPath.substringAfterLast("/"))
            if (bytes == null) respondError(HttpStatusCode.ServiceUnavailable) else respond(bytes)
          },
        ),
      baseUrl = "https://example.test/feeds/",
      nowEpochSeconds = { lFeed.header.timestamp },
    )

  @Test
  fun fetchCombinesTheLFeedAndAlerts() = runTest {
    val snapshot =
      client { path -> if (path.contains("gtfs-l")) resource("l-feed.pb") else resource("alerts-feed.pb") }
        .fetch(listOf("L"))
    assertTrue(snapshot.arrivals.size > 50)
    assertEquals(false, snapshot.partial)
    assertEquals(lFeed.header.timestamp, snapshot.fetchedAtEpochSeconds)
  }

  @Test
  fun aFailedFeedLeavesTheRestAndIsFlagged() = runTest {
    val snapshot = client { path -> if (path.contains("gtfs-l")) resource("l-feed.pb") else null }.fetch(listOf("L"))
    assertTrue(snapshot.arrivals.size > 50)
    assertTrue(snapshot.alerts.isEmpty())
    assertTrue(snapshot.partial)
  }

  @Test
  fun everyFeedFailingThrows() = runTest {
    assertFailsWith<IllegalStateException> { client { null }.fetch(listOf("L")) }
  }
}
