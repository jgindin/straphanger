package com.straphanger.app.data.gtfs

import com.straphanger.app.testutil.BundledNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Express or local on a ride with a change, on the generated network: the rider's trip from 14 St
 * (A/C/E, A31) to Van Cortlandt Park-242 St (101), changing from the A to the 1 at 168 St.
 */
class MultiLegRouteChoiceTest {

  private val engine = BundledNetwork.engine()

  // 2026-11-09 14:00:00 EST (Monday) -> weekday daytime
  private val daytimeEpochMs = 1794250800000L

  private fun ridersTrip(durationMinutes: Double?): ResolvedTripResult =
    engine.resolveOriginToDestinationAuto(
      originStationId = FOURTEENTH_ST,
      destinationStationId = VAN_CORTLANDT_242,
      timestampEpochMs = daytimeEpochMs,
      actualDurationMinutes = durationMinutes,
    )

  private fun describe(trip: ResolvedTripResult): String =
    trip.legs.joinToString(" | ") {
      "${it.routeId}${if (it.isExpress) " exp" else ""}: ${it.fromStationId}->${it.toStationId}"
    } + " candidates=" + trip.ambiguousCandidates

  @Test
  fun aRideWithAChangeGuessesTheFirstLegFromItsOwnShareOfTheTime() {
    val trip = ridersTrip(durationMinutes = 55.0)
    assertEquals(2, trip.legs.size, describe(trip))
    assertEquals("A", trip.legs[0].routeId, describe(trip))
    assertTrue(trip.legs[0].isExpress, describe(trip))
    assertEquals("1", trip.legs[1].routeId, describe(trip))
  }

  @Test
  fun theFirstLegOffersExpressAndLocalAndTheOneLegOffersNone() {
    val trip = ridersTrip(durationMinutes = 55.0)
    assertTrue(trip.isAmbiguous, describe(trip))
    val candidates = trip.ambiguousCandidates
    assertEquals(listOf("A" to true, "C" to false), candidates.map { it.routeId to it.isExpress })
    assertTrue(candidates.all { it.legIndex == 0 }, describe(trip))
    assertTrue(candidates.all { it.fromStationId == FOURTEENTH_ST }, describe(trip))
  }

  @Test
  fun withNoDurationTheFirstLegIsTheExpress() {
    val trip = ridersTrip(durationMinutes = null)
    assertEquals("A", trip.legs[0].routeId, describe(trip))
    assertTrue(trip.legs[0].isExpress, describe(trip))
  }

  @Test
  fun aShareOfNoTimeFallsBackToTheExpress() {
    val trip = ridersTrip(durationMinutes = 5.0)
    assertEquals("A", trip.legs[0].routeId, describe(trip))
    assertTrue(trip.legs[0].isExpress, describe(trip))
  }

  @Test
  fun aLegNoTrackJoinsIsLeftOutAndTheOthersAreNumberedFromZero() {
    val trip =
      engine.resolveTrip(
        legs =
          listOf(
            TripLegRequest("no-such-station", "nowhere"),
            TripLegRequest(FOURTEENTH_ST, "A09"),
            TripLegRequest("112", VAN_CORTLANDT_242),
          ),
        timestampEpochMs = daytimeEpochMs,
        actualDurationMinutes = 55.0,
      )

    assertEquals(listOf(0, 1), trip.legs.map { it.legIndex }, describe(trip))
    assertEquals(listOf("A", "1"), trip.legs.map { it.routeId }, describe(trip))
    assertTrue(trip.ambiguousCandidates.all { it.legIndex == 0 }, describe(trip))
    assertEquals(setOf(0, 1), trip.stationStops.map { it.legIndex }.toSet())
    assertEquals(StopRole.BOARDED, trip.stationStops.first().role)
    assertEquals(StopRole.EXITED, trip.stationStops.last().role)
  }

  @Test
  fun aLegPinnedToTheLocalCountsTheLocalsTimeWhenSharingOutTheRide() {
    // 59 St-Columbus Circle to 42 St on the A or C, then the R local (not the N) to Union Sq.
    fun trip(secondLeg: TripLegRequest) =
      engine.resolveTrip(
        legs = listOf(TripLegRequest("A24", "A27"), secondLeg),
        timestampEpochMs = daytimeEpochMs,
        actualDurationMinutes = 12.0,
      )

    // Counting the R's 7.3 minutes leaves the first leg 0.7, nearer the A's 2.6 than the C's 4.5.
    val pinned = trip(TripLegRequest("R16", "R20", preferredRouteId = "R", forceExpress = false))
    assertEquals("A" to "R", pinned.legs[0].routeId to pinned.legs[1].routeId, describe(pinned))
    // Left to the app, the second leg counts as the N express (4.2) and the first leg gets 3.8.
    val free = trip(TripLegRequest("R16", "R20"))
    assertEquals("C", free.legs[0].routeId, describe(free))
  }

  private companion object {
    const val FOURTEENTH_ST = "A31"
    const val VAN_CORTLANDT_242 = "101"
  }
}
