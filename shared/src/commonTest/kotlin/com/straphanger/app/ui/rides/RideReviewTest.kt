package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.AmbiguousRouteCandidate
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.testutil.TestBasemap
import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.ui.StraphangerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers

class RideReviewTest {

  private fun ride(
    candidates: List<AmbiguousRouteCandidate>,
    source: RideSource = RideSource.PASSIVE_GPS,
  ) =
    RideEntity(
      rideId = "r1",
      startTimeEpochMs = 0L,
      endTimeEpochMs = 600_000L,
      originStationId = "635",
      destinationStationId = "626",
      totalDistanceMiles = 3.5,
      durationMinutes = 10.0,
      source = source,
      needsReview = true,
      ambiguousCandidates = candidates,
    )

  @Test
  fun candidatesDriveTheConfirmOptions() {
    val review =
      reviewFor(
        ride(
          listOf(
            AmbiguousRouteCandidate(
              "5",
              isExpress = true,
              label = "5 Express",
              estimatedMinutes = 9.0,
              stoppedStationCount = 4,
            ),
            AmbiguousRouteCandidate(
              "6",
              isExpress = false,
              label = "6 Local",
              estimatedMinutes = 14.0,
              stoppedStationCount = 10,
            ),
          ),
        ),
      )

    assertEquals(
      listOf(
        ReviewAction.ConfirmCandidate(
          index = 0,
          routeId = "5",
          isExpress = true,
          label = "Confirm 5 Express",
        ),
        ReviewAction.ConfirmCandidate(
          index = 1,
          routeId = "6",
          isExpress = false,
          label = "Confirm 6 Local",
        ),
        ReviewAction.KeepAsIs,
      ),
      review.actions,
    )
    assertTrue(review.prompt.contains("express or local", ignoreCase = true), review.prompt)
  }

  private fun candidate(routeId: String, express: Boolean, legIndex: Int, from: String?) =
    AmbiguousRouteCandidate(
      routeId,
      isExpress = express,
      label = "$routeId ${if (express) "Express" else "Local"}",
      estimatedMinutes = if (express) 2.6 else 4.5,
      stoppedStationCount = 3,
      legIndex = legIndex,
      fromStationId = from,
    )

  @Test
  fun aRideWithAChangeGroupsItsCandidatesByLeg() {
    val names = mapOf("A24" to "59 St-Columbus Circle", "R16" to "Times Sq-42 St")
    val oneLeg =
      reviewFor(ride(listOf(candidate("A", true, 0, "A24"), candidate("C", false, 0, "A24"))), 2) {
        names.getValue(it)
      }
    assertEquals(mapOf(0 to "From 59 St-Columbus Circle:"), oneLeg.legHeadings)
    assertEquals(
      "Express or local? A Express or C Local both serve the leg from 59 St-Columbus Circle.",
      oneLeg.prompt,
    )
    assertEquals(
      ReviewAction.ConfirmCandidate(1, "C", false, "C Local · 5 min", legIndex = 0),
      oneLeg.actions[1],
    )

    val twoLegs =
      reviewFor(
        ride(
          listOf(
            candidate("A", true, 0, "A24"),
            candidate("C", false, 0, "A24"),
            candidate("N", true, 1, "R16"),
            candidate("R", false, 1, null),
          ),
        ),
        2,
      ) {
        names.getValue(it)
      }
    assertEquals(
      mapOf(0 to "From 59 St-Columbus Circle:", 1 to "From Times Sq-42 St:"),
      twoLegs.legHeadings,
    )
    assertEquals("Express or local? Choose the train for each leg.", twoLegs.prompt)
    // Rides stored before 0.37 have no start station on their candidates.
    val unnamed =
      reviewFor(ride(listOf(candidate("N", true, 1, null), candidate("R", false, 1, null))), 2)
    assertEquals(mapOf(1 to "Leg 2:"), unnamed.legHeadings)
    assertEquals("Express or local? N Express or R Local both serve leg 2.", unnamed.prompt)
    assertEquals(
      listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1),
      twoLegs.actions.filterIsInstance<ReviewAction.ConfirmCandidate>().map {
        it.legIndex to it.index
      },
    )
    assertEquals(ReviewAction.KeepAsIs, twoLegs.actions.last())
  }

  @Test
  fun aRideWithoutCandidatesOffersOnlyKeepAsIs() {
    val review = reviewFor(ride(emptyList(), source = RideSource.GMAPS_SHARE))
    assertEquals(listOf<ReviewAction>(ReviewAction.KeepAsIs), review.actions)
    assertFalse(review.prompt.contains("express", ignoreCase = true), review.prompt)
    assertTrue(review.prompt.contains("more than one station"), review.prompt)
  }

  @Test
  fun keepAsIsClearsReviewWithoutChangingTheLegs() {
    val vm =
      StraphangerViewModel(
        backend = InMemoryPersistenceBackend(),
        dispatcher = Dispatchers.Unconfined,
        networkLoader = { TestNetwork.data },
        basemapLoader = { TestBasemap.empty },
      )
    val ride = vm.simulateAmbiguousPassiveRide()!!
    assertTrue(ride.ride.needsReview)

    vm.keepRideAsIs(ride.ride.rideId)

    val kept = vm.dao.getRideById(ride.ride.rideId)!!
    assertFalse(kept.ride.needsReview)
    assertEquals(ride.legs, kept.legs)
    assertEquals(ride.segments, kept.segments)
    assertEquals(0, vm.uiState.value.stats.pendingReviewCount)
  }

  @Test
  fun theSimulatedRideIsAmbiguousEvenWhenNowIsLateAtNight() {
    // Only local trains run late at night, so a ride timed "now" would have nothing to confirm.
    val vm =
      StraphangerViewModel(
        backend = InMemoryPersistenceBackend(),
        dispatcher = Dispatchers.Unconfined,
        networkLoader = { TestNetwork.data },
        basemapLoader = { TestBasemap.empty },
        clock = FixedClock(Instant.parse("2026-11-18T05:00:00Z")),
      )

    val ride = vm.simulateAmbiguousPassiveRide()!!

    assertTrue(ride.ride.ambiguousCandidates.isNotEmpty())
  }

  private fun viewModel() =
    StraphangerViewModel(
      backend = InMemoryPersistenceBackend(),
      dispatcher = Dispatchers.Unconfined,
      networkLoader = { TestNetwork.data },
      basemapLoader = { TestBasemap.empty },
    )

  @Test
  fun changingTheStationsRoutesTheRideAgainAndKeepsItsTimes() {
    val vm = viewModel()
    val ride = vm.simulateAmbiguousPassiveRide()!!.ride

    // Ridden the other way: from 86 St down to 14 St-Union Sq.
    assertTrue(vm.changeRideStations(ride.rideId, ride.destinationStationId, ride.originStationId))

    val changed = vm.dao.getRideById(ride.rideId)!!.ride
    assertEquals(ride.destinationStationId, changed.originStationId)
    assertEquals(ride.originStationId, changed.destinationStationId)
    assertEquals(ride.startTimeEpochMs, changed.startTimeEpochMs)
    assertEquals(ride.endTimeEpochMs, changed.endTimeEpochMs)
    assertEquals(1, vm.dao.getAllRides().size)
  }

  @Test
  fun changingToTheSameStationOrNoRouteLeavesTheRideAlone() {
    val vm = viewModel()
    val before = vm.simulateAmbiguousPassiveRide()!!

    assertFalse(vm.changeRideStations(before.ride.rideId, "635", "635"))
    assertFalse(vm.changeRideStations(before.ride.rideId, "635", "no-such-station"))
    assertFalse(vm.changeRideStations("no-such-ride", "635", "626"))

    assertEquals(before, vm.dao.getRideById(before.ride.rideId))
  }
}
