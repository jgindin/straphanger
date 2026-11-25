package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.ServicePeriod
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RideLegEntity
import com.straphanger.app.data.local.RideSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A ride [id] at [start] over [legs], each a (route, from, to); the stations come from the legs.
 */
internal fun rideOver(
  id: String,
  start: Long,
  vararg legs: Triple<String, String, String>,
): CompleteRideRecord =
  CompleteRideRecord(
    ride =
      RideEntity(
        rideId = id,
        startTimeEpochMs = start,
        endTimeEpochMs = start + 600_000L,
        originStationId = legs.first().second,
        destinationStationId = legs.last().third,
        totalDistanceMiles = 1.0,
        durationMinutes = 10.0,
        source = RideSource.MANUAL,
      ),
    legs =
      legs.mapIndexed { index, (route, from, to) ->
        RideLegEntity("${id}_$index", id, index, route, false, ServicePeriod.DAYTIME, from, to, 1.0)
      },
    segments = emptyList(),
    stationStops = emptyList(),
  )

class TripSuggestionTest {
  private val names = mapOf("A" to "168 St", "B" to "14 St", "C" to "86 St", "D" to "Bedford Av")
  private val name: (String) -> String? = names::get

  @Test
  fun theMostTakenTripComesFirstThenTheMostRecent() {
    val rides =
      listOf(
        rideOver("1", 100, Triple("1", "A", "B")),
        rideOver("2", 200, Triple("6", "B", "C")),
        rideOver("3", 300, Triple("1", "A", "B")),
        rideOver("4", 400, Triple("L", "D", "B")),
      )

    val trips = suggestedTrips(rides, name)

    assertEquals(
      listOf("168 St → 14 St", "Bedford Av → 14 St", "14 St → 86 St"),
      trips.map { it.label },
    )
  }

  @Test
  fun eachTripIsOfferedOnceWithItsChange() {
    val rides =
      listOf(
        rideOver("1", 100, Triple("L", "D", "B"), Triple("4", "B", "C")),
        rideOver("2", 200, Triple("L", "D", "B"), Triple("6", "B", "C")),
        rideOver("3", 300, Triple("L", "D", "C")),
      )

    val trips = suggestedTrips(rides, name)

    assertEquals(2, trips.size)
    val viaUnionSq = trips.first()
    assertEquals("B", viaUnionSq.transferStationId)
    assertEquals("Bedford Av → 86 St via 14 St", viaUnionSq.label)
    assertNull(viaUnionSq.routeId, "two lines, so no single line")
    assertNull(trips[1].transferStationId)
  }

  @Test
  fun aTripAlwaysOnOneLineNamesIt() {
    val oneLine =
      listOf(rideOver("1", 100, Triple("1", "A", "B")), rideOver("2", 200, Triple("1", "A", "B")))
    assertEquals("1", suggestedTrips(oneLine, name).single().routeId)

    val twoLines = oneLine + rideOver("3", 300, Triple("2", "A", "B"))
    assertNull(suggestedTrips(twoLines, name).single().routeId)
  }

  @Test
  fun atMostLimitTripsAndNoneWithoutRides() {
    val rides =
      (0 until 10).map { rideOver("$it", it.toLong(), Triple("1", "A", "B")) } +
        listOf(rideOver("x", 50, Triple("6", "B", "C")), rideOver("y", 60, Triple("L", "D", "B")))
    assertEquals(2, suggestedTrips(rides, name, limit = 2).size)
    assertEquals("168 St → 14 St", suggestedTrips(rides, name, limit = 2).first().label)
    assertTrue(suggestedTrips(emptyList(), name).isEmpty())
  }

  @Test
  fun ridesWithUnknownStationsAreLeftOut() {
    val rides =
      listOf(
        rideOver("1", 100, Triple("1", "A", "NOPE")),
        rideOver("2", 200, Triple("1", "NOPE", "B")),
        rideOver("3", 300, Triple("1", "A", "NOPE"), Triple("1", "NOPE", "B")),
        rideOver("4", 400, Triple("6", "B", "C")),
      )
    assertEquals(listOf("14 St → 86 St"), suggestedTrips(rides, name).map { it.label })
  }

  @Test
  fun ridesStillToReviewAndRidesWithTwoChangesAreLeftOut() {
    val toReview = rideOver("1", 100, Triple("1", "A", "B"))
    val rides =
      listOf(
        toReview.copy(ride = toReview.ride.copy(needsReview = true)),
        rideOver("2", 200, Triple("1", "A", "B"), Triple("L", "B", "D"), Triple("6", "D", "C")),
        rideOver("3", 300, Triple("6", "B", "C")),
      )
    assertEquals(listOf("14 St → 86 St"), suggestedTrips(rides, name).map { it.label })
  }
}
