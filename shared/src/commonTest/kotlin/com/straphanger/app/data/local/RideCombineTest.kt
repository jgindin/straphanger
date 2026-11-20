package com.straphanger.app.data.local

import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Combining a ride the app split in two (125 St → 103 St, then 103 St → 14 St on the 8 Av line),
 * and choosing the line when the stations of a ride change.
 */
class RideCombineTest {
  private val engine = TestNetwork.engine()

  // 9:00 on a Wednesday in New York, so both the A express and the C local run.
  private val nineAm = Instant.parse("2026-11-11T14:00:00Z").toEpochMilliseconds()
  private val minute = 60_000L

  private fun ride(
    id: String,
    origin: String,
    destination: String,
    startMinute: Long,
    endMinute: Long,
    notes: String = "",
  ): CompleteRideRecord {
    val start = nineAm + startMinute * minute
    val end = nineAm + endMinute * minute
    return SubwayDao.buildCompleteRideRecord(
      rideId = id,
      startTimeEpochMs = start,
      endTimeEpochMs = end,
      source = RideSource.PASSIVE_GPS,
      resolved =
        engine.resolveOriginToDestinationAuto(
          originStationId = origin,
          destinationStationId = destination,
          timestampEpochMs = start,
          actualDurationMinutes = (endMinute - startMinute).toDouble(),
        ),
      graphEngine = engine,
      notes = notes,
    )
  }

  private fun daoWith(vararg rides: CompleteRideRecord) =
    SubwayDao(InMemoryPersistenceBackend()).also { it.insertOrReplaceRides(rides.toList()) }

  private val uptown = ride("first", "A15", "A18", startMinute = 0, endMinute = 6, notes = "late")
  private val downtown = ride("second", "A18", "A31", startMinute = 9, endMinute = 30)

  @Test
  fun theRideFromWhereTheFirstEndedSoonAfterIsTheOneToCombine() {
    val rides = listOf(downtown, uptown, ride("other", "635", "626", 40, 50))

    assertEquals(downtown, nextRideToCombine(rides, uptown.ride, engine))
    assertNull(nextRideToCombine(rides, downtown.ride, engine))
  }

  @Test
  fun aRideMoreThanHalfAnHourLaterIsNotCombined() {
    val later = ride("second", "A18", "A31", startMinute = 37, endMinute = 55)

    assertNull(nextRideToCombine(listOf(uptown, later), uptown.ride, engine))
  }

  @Test
  fun aRideFromAnotherStationIsNotCombined() {
    val elsewhere = ride("second", "A24", "A31", startMinute = 9, endMinute = 20)

    assertNull(nextRideToCombine(listOf(uptown, elsewhere), uptown.ride, engine))
  }

  @Test
  fun aRideThatStartedWellBeforeTheFirstEndedIsNotCombined() {
    val early = ride("second", "A18", "A31", startMinute = 3, endMinute = 20)

    assertNull(nextRideToCombine(listOf(uptown, early), uptown.ride, engine))
  }

  @Test
  fun combiningKeepsTheFirstStartAndTheSecondEndAndLeavesOneRide() {
    val dao = daoWith(uptown, downtown)

    val combined = dao.combineRides("first", "second", engine)!!

    val only = dao.getAllRides().single()
    assertEquals(combined, only)
    assertEquals("first", only.ride.rideId)
    assertEquals("A15", only.ride.originStationId)
    assertEquals("A31", only.ride.destinationStationId)
    assertEquals(uptown.ride.startTimeEpochMs, only.ride.startTimeEpochMs)
    assertEquals(downtown.ride.endTimeEpochMs, only.ride.endTimeEpochMs)
    assertEquals(RideSource.PASSIVE_GPS, only.ride.source)
    assertEquals("late", only.ride.notes)
    // The A express and the C local both run 125 St to 14 St, so the rider is asked which.
    assertTrue(only.ride.needsReview)
    assertEquals(listOf("A", "C"), only.ride.ambiguousCandidates.map { it.routeId })
  }

  @Test
  fun combiningJoinsBothRidesNotes() {
    val dao = daoWith(uptown, downtown.copy(ride = downtown.ride.copy(notes = "crowded")))

    assertEquals("late; crowded", dao.combineRides("first", "second", engine)!!.ride.notes)
  }

  @Test
  fun combiningAMissingOrTheSameRideChangesNothing() {
    val dao = daoWith(uptown, downtown)
    val before = dao.getAllRides()

    assertNull(dao.combineRides("first", "no-such-ride", engine))
    assertNull(dao.combineRides("no-such-ride", "second", engine))
    assertNull(dao.combineRides("first", "first", engine))

    assertEquals(before, dao.getAllRides())
  }

  @Test
  fun choosingALineWhenChangingStationsStoresThatLine() {
    val dao = daoWith(ride("r", "A24", "A31", startMinute = 0, endMinute = 12))

    val local = dao.changeRideStations("r", "A15", "A31", engine, RouteChoice("C", false))!!
    assertEquals("C", local.legs.single().routeId)
    assertFalse(local.legs.single().isExpress)
    assertFalse(local.ride.needsReview)
    assertEquals(TestNetwork.eighthAvLocal.size, local.stationStops.size)

    val express = dao.changeRideStations("r", "A15", "A31", engine, RouteChoice("A", true))!!
    assertEquals("A", express.legs.single().routeId)
    assertTrue(express.legs.single().isExpress)
    assertFalse(express.ride.needsReview)
    assertEquals(dao.getAllRides().single(), express)
  }

  @Test
  fun lettingTheAppDecideKeepsTheExpressOrLocalQuestion() {
    val dao = daoWith(ride("r", "A24", "A31", startMinute = 0, endMinute = 12))

    val changed = dao.changeRideStations("r", "A15", "A31", engine)!!

    assertTrue(changed.ride.needsReview)
    assertEquals(2, changed.ride.ambiguousCandidates.size)
  }
}
