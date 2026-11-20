package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.AmbiguousRouteCandidate
import com.straphanger.app.data.gtfs.ServicePeriod
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RideLegEntity
import com.straphanger.app.data.local.RideSource
import kotlin.test.Test
import kotlin.test.assertEquals

class RideNotificationTextTest {

  private val stationNames = mapOf("A31" to "14 St", "A09" to "168 St")
  private val names: (String) -> String = { stationNames.getValue(it) }

  private fun record(
    legs: List<String>,
    minutes: Double = 31.6,
    needsReview: Boolean = false,
    candidates: List<AmbiguousRouteCandidate> = emptyList(),
  ) =
    CompleteRideRecord(
      ride =
        RideEntity(
          rideId = "r1",
          startTimeEpochMs = 0L,
          endTimeEpochMs = 600_000L,
          originStationId = "A31",
          destinationStationId = "A09",
          totalDistanceMiles = 3.5,
          durationMinutes = minutes,
          source = RideSource.PASSIVE_GPS,
          needsReview = needsReview,
          ambiguousCandidates = candidates,
        ),
      legs =
        legs.mapIndexed { index, routeId ->
          RideLegEntity(
            legId = "l$index",
            rideId = "r1",
            legIndex = index,
            routeId = routeId,
            isExpress = false,
            servicePeriod = ServicePeriod.DAYTIME,
            fromStationId = "A31",
            toStationId = "A09",
            distanceMiles = 1.0,
          )
        },
      segments = emptyList(),
      stationStops = emptyList(),
    )

  private val candidate =
    AmbiguousRouteCandidate(
      "A",
      isExpress = true,
      label = "A Express",
      estimatedMinutes = 30.0,
      stoppedStationCount = 5,
    )

  @Test
  fun oneLegRideShowsLineStationsAndMinutes() {
    assertEquals(
      RideNotificationText("A · 14 St → 168 St", "32 min"),
      rideNotificationText(record(legs = listOf("A")), names),
    )
  }

  @Test
  fun transferJoinsLinesAndDropsExpressSuffix() {
    val text = rideNotificationText(record(legs = listOf("6X", "L")), names)
    assertEquals("6 → L · 14 St → 168 St", text.title)
  }

  @Test
  fun repeatedLineIsShownOnce() {
    val text = rideNotificationText(record(legs = listOf("A", "A")), names)
    assertEquals("A · 14 St → 168 St", text.title)
  }

  @Test
  fun ambiguousRideAsksExpressOrLocal() {
    val text =
      rideNotificationText(
        record(legs = listOf("A"), needsReview = true, candidates = listOf(candidate)),
        names,
      )
    assertEquals("32 min · Express or local? Tap to confirm", text.text)
  }

  @Test
  fun flaggedRideWithoutCandidatesAsksToCheck() {
    val text = rideNotificationText(record(legs = listOf("A"), needsReview = true), names)
    assertEquals("32 min · Tap to check this ride", text.text)
  }

  @Test
  fun aVeryShortRideSaysOneMinute() {
    val text = rideNotificationText(record(legs = listOf("A"), minutes = 0.2), names)
    assertEquals("1 min", text.text)
  }
}
