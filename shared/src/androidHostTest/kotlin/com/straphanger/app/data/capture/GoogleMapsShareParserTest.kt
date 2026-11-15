package com.straphanger.app.data.capture

import com.straphanger.app.data.local.RideSource
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.testutil.FixedClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class GoogleMapsShareParserTest {

  private val engine = BundledNetwork.engine()

  // 2026-11-09 14:00 EST, weekday daytime
  private val daytimeEpochMs = 1794250800000L

  private fun parser(
    http: HttpClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
  ) = GoogleMapsShareParser(engine, http, FixedClock(Instant.fromEpochMilliseconds(daytimeEpochMs)))

  @Test
  fun deepLinkWithStationIdsAndRoute() = runTest {
    val record = parser().parseAndResolveSharedRoute("straphanger://share?from=635&to=626&route=4")
    assertNotNull(record)
    assertEquals("635", record.ride.originStationId)
    assertEquals("626", record.ride.destinationStationId)
    assertEquals("4", record.legs.single().routeId)
    assertEquals(RideSource.GMAPS_SHARE, record.ride.source)
    assertTrue(record.ride.rideId.startsWith("share"))
    // The parser's clock gives the start time when none is passed.
    assertEquals(daytimeEpochMs, record.ride.startTimeEpochMs)
    assertFalse(record.ride.needsReview)
  }

  @Test
  fun legacyDeepLinkSchemeWithEncodedCoordinates() = runTest {
    // Coordinates resolve to the nearest station; the old nycsubway:// scheme is still accepted.
    val record =
      parser()
        .parseAndResolveSharedRoute(
          "nycsubway://share?from=40.734673%2C-73.989951&to=40.779492,-73.955589&route=6",
          daytimeEpochMs,
        )
    assertNotNull(record)
    assertEquals("626", record.ride.destinationStationId)
    assertEquals("14 St-Union Sq", engine.getStation(record.ride.originStationId)?.name)
  }

  @Test
  fun deepLinkWithoutBothStationsIsNotARide() = runTest {
    val p = parser()
    assertNull(p.parseAndResolveSharedRoute("straphanger://share", daytimeEpochMs))
    assertNull(p.parseAndResolveSharedRoute("straphanger://share?from=635", daytimeEpochMs))
    assertNull(p.parseAndResolveSharedRoute("straphanger://share?=635&to", daytimeEpochMs))
    assertNull(
      p.parseAndResolveSharedRoute("straphanger://share?from=635&to=635", daytimeEpochMs),
      "the same station at both ends",
    )
    assertNull(
      p.parseAndResolveSharedRoute("straphanger://share?from=XYZ&to=Nowhere+Land", daytimeEpochMs)
    )
  }

  @Test
  fun notificationWithEndTimeKeepsItsTimes() = runTest {
    val end = daytimeEpochMs + 20 * 60_000L
    val record =
      parser()
        .parseAndResolveSharedRoute(
          "GMAPS_NOTIFICATION: 6 Train | Board at 14 St-Union Sq • Exit at Grand Central-42 St",
          daytimeEpochMs,
          end,
        )
    assertNotNull(record)
    assertEquals(RideSource.GMAPS_NOTIFICATION, record.ride.source)
    assertEquals(daytimeEpochMs, record.ride.startTimeEpochMs)
    assertEquals(end, record.ride.endTimeEpochMs)
    assertEquals("6", record.legs.single().routeId)
  }

  @Test
  fun directionsUrlWithoutShortLink() = runTest {
    val record =
      parser()
        .parseAndResolveSharedRoute(
          "https://www.google.com/maps/dir/Bedford+Av/14%20St-Union%20Sq?entry=ttu",
          daytimeEpochMs,
        )
    assertNotNull(record)
    assertEquals("L08", record.ride.originStationId)
    assertTrue(record.ride.rideId.startsWith("share_url"))
  }

  @Test
  fun shortLinkThatCannotBeExpandedIsNotARide() = runTest {
    val failing = HttpClient(MockEngine { throw IOException("offline") })
    assertNull(
      parser(failing).parseAndResolveSharedRoute("https://maps.app.goo.gl/abc123", daytimeEpochMs)
    )
    val rejected = HttpClient(MockEngine { throw IllegalArgumentException("bad url") })
    assertNull(
      parser(rejected).parseAndResolveSharedRoute("https://maps.app.goo.gl/abc123", daytimeEpochMs)
    )
    val noLocation = HttpClient(MockEngine { respond("", HttpStatusCode.Found) })
    assertNull(
      parser(noLocation).parseAndResolveSharedRoute("https://goo.gl/maps/xyz", daytimeEpochMs)
    )
  }

  @Test
  fun shortLinkRedirectIsFollowedOnce() = runTest {
    val http =
      HttpClient(
        MockEngine {
          respond(
            "",
            HttpStatusCode.Found,
            headersOf(
              HttpHeaders.Location,
              "https://www.google.com/maps/dir/Bedford+Av/Grand+Central-42+St/",
            ),
          )
        }
      ) {
        followRedirects = false
      }
    val record = parser(http).parseAndResolveSharedRoute("https://goo.gl/maps/xyz", daytimeEpochMs)
    assertNotNull(record)
    assertEquals("L08", record.ride.originStationId)
    assertEquals("631", record.ride.destinationStationId)
  }

  @Test
  fun textThatNamesNoStationsIsNotARide() = runTest {
    val p = parser()
    assertNull(p.parseAndResolveSharedRoute("", daytimeEpochMs))
    assertNull(p.parseAndResolveSharedRoute("Meet me for lunch at noon", daytimeEpochMs))
    assertNull(p.parseAndResolveSharedRoute("From Atlantis to El Dorado", daytimeEpochMs))
  }

  @Test
  fun summaryOnALaterLineIsFound() = runTest {
    val record =
      parser()
        .parseAndResolveSharedRoute(
          "My trip\nFrom Times Sq-42 St to 86 St via 1 train\nhttps://maps.app.goo.gl/zzz",
          daytimeEpochMs,
        )
    assertNotNull(record)
    assertEquals("127", record.ride.originStationId)
    assertTrue(record.legs.first().routeId == "1")
  }
}
