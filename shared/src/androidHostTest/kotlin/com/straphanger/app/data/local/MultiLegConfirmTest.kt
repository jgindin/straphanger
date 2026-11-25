package com.straphanger.app.data.local

import com.straphanger.app.testutil.BundledNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Confirming express or local on a ride with a change, on the generated network: 14 St (A31) to
 * Van Cortlandt Park-242 St (101), the A or C to 168 St and then the 1.
 */
class MultiLegConfirmTest {
  private val engine = BundledNetwork.engine()
  private val nineAm = Instant.parse("2026-11-11T14:00:00Z").toEpochMilliseconds()

  private fun ride(
    id: String,
    origin: String,
    destination: String,
    minutes: Int,
    source: RideSource = RideSource.PASSIVE_GPS,
  ) =
    SubwayDao.buildCompleteRideRecord(
      rideId = id,
      startTimeEpochMs = nineAm,
      endTimeEpochMs = nineAm + minutes * 60_000L,
      source = source,
      forceNeedsReview = if (source == RideSource.MANUAL) false else null,
      resolved =
        engine.resolveOriginToDestinationAuto(
          originStationId = origin,
          destinationStationId = destination,
          timestampEpochMs = nineAm,
          actualDurationMinutes = minutes.toDouble(),
        ),
      graphEngine = engine,
    )

  private fun ridersTrip() = ride("r", "A31", "101", minutes = 55)

  private fun daoWith(vararg records: CompleteRideRecord) =
    SubwayDao().apply { insertOrReplaceRides(records.toList()) }

  @Test
  fun confirmingTheLocalOnTheFirstLegKeepsTheSecondOnTheOne() {
    val dao = daoWith(ridersTrip())
    assertTrue(dao.getRideById("r")!!.ride.needsReview)

    val confirmed = dao.confirmAmbiguousRide("r", mapOf(0 to RouteChoice("C", false)), engine)!!

    assertEquals(listOf("C" to false, "1" to false), confirmed.legs.map { it.routeId to it.isExpress })
    assertFalse(confirmed.ride.needsReview)
    assertTrue(confirmed.ride.ambiguousCandidates.isEmpty())
    assertEquals(confirmed, dao.getRideById("r"))
  }

  @Test
  fun confirmingTheExpressOnTheFirstLegStoresTheA() {
    val dao = daoWith(ridersTrip())

    val confirmed = dao.confirmAmbiguousRide("r", mapOf(0 to RouteChoice("A", true)), engine)!!

    assertEquals(listOf("A" to true, "1" to false), confirmed.legs.map { it.routeId to it.isExpress })
    assertFalse(confirmed.ride.needsReview)
  }

  @Test
  fun confirmingOneOfTwoLegsThatAskKeepsTheOtherQuestionOpen() {
    // 59 St-Columbus Circle to 14 St-Union Sq: the A or C to 42 St, then the N or R.
    val dao = daoWith(ride("two", "A24", "635", minutes = 25))
    assertEquals(setOf(0, 1), dao.getRideById("two")!!.ride.ambiguousCandidates.map { it.legIndex }.toSet())

    val half = dao.confirmAmbiguousRide("two", mapOf(0 to RouteChoice("C", false)), engine)!!
    assertEquals("C", half.legs[0].routeId)
    assertTrue(half.ride.needsReview)
    assertEquals(setOf(1), half.ride.ambiguousCandidates.map { it.legIndex }.toSet())

    val both =
      dao.confirmAmbiguousRide(
        "two",
        mapOf(0 to RouteChoice("C", false), 1 to RouteChoice("N", true)),
        engine,
      )!!
    assertEquals(listOf("C" to false, "N" to true), both.legs.map { it.routeId to it.isExpress })
    assertFalse(both.ride.needsReview)
  }

  @Test
  fun changingStationsWithAChoiceForTheFirstLegStoresIt() {
    val dao = daoWith(ride("r", "A31", "A09", minutes = 30))

    val changed =
      dao.changeRideStations("r", "A31", "101", engine, mapOf(0 to RouteChoice("A", true)))!!

    assertEquals(listOf("A" to true, "1" to false), changed.legs.map { it.routeId to it.isExpress })
    assertFalse(changed.ride.needsReview)
  }

  @Test
  fun changingStationsIgnoresAChoiceNotOfferedForItsLeg() {
    val dao = daoWith(ride("r", "A31", "A09", minutes = 30))

    // The A is offered on the first leg, not the second.
    val changed =
      dao.changeRideStations("r", "A31", "101", engine, mapOf(1 to RouteChoice("A", true)))!!

    assertTrue(changed.ride.needsReview)
    assertEquals(setOf(0), changed.ride.ambiguousCandidates.map { it.legIndex }.toSet())
  }

  @Test
  fun aManualRideEditedToATripWithAChangeAndLeftToTheAppStaysReviewed() {
    val dao = daoWith(ride("m", "A31", "A09", minutes = 55, source = RideSource.MANUAL))
    assertFalse(dao.getRideById("m")!!.ride.needsReview)

    val changed = dao.changeRideStations("m", "A31", "101", engine)!!

    assertEquals(listOf("A" to true, "1" to false), changed.legs.map { it.routeId to it.isExpress })
    assertFalse(changed.ride.needsReview)
    assertEquals(changed, dao.getRideById("m"))
  }

  @Test
  fun aStoredRideWhoseCandidatesHaveNoLegIndexStillLoads() {
    val backend = InMemoryPersistenceBackend()
    SubwayDao(backend).insertOrReplaceRide(ride("old", "A31", "A15", minutes = 20))
    val stored = backend.loadSnapshotJson()!!
    // Rides saved before 0.37 had candidates without a leg index or a start station.
    val old = stored.replace(Regex(""","legIndex":\d+,"fromStationId":(null|"[^"]*")"""), "")
    assertTrue(old != stored && "\"stoppedStationCount\":5}" in old, old)

    val loaded = SubwayDao(InMemoryPersistenceBackend(old)).getRideById("old")!!

    assertEquals(listOf("A", "C"), loaded.ride.ambiguousCandidates.map { it.routeId })
    assertTrue(loaded.ride.ambiguousCandidates.all { it.legIndex == 0 && it.fromStationId == null })
  }
}
