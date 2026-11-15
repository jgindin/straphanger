package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.AmbiguousRouteCandidate
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.testutil.TestBasemap
import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.ui.StraphangerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
          )
        )
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
}
