package com.straphanger.app.data.local

import com.straphanger.app.testutil.TestBasemap
import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.ui.StraphangerViewModel
import com.straphanger.app.ui.rides.reviewFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

class RideOverlapTest {
  private fun viewModel() =
    StraphangerViewModel(
      backend = InMemoryPersistenceBackend(),
      dispatcher = Dispatchers.Unconfined,
      networkLoader = { TestNetwork.data },
      basemapLoader = { TestBasemap.empty },
    )

  /** A quiet copy of the simulated ride, moved [shiftMs] later and under a new id. */
  private fun CompleteRideRecord.movedBy(id: String, shiftMs: Long) =
    copy(
      ride =
        ride.copy(
          rideId = id,
          startTimeEpochMs = ride.startTimeEpochMs + shiftMs,
          endTimeEpochMs = ride.endTimeEpochMs + shiftMs,
          needsReview = false,
          ambiguousCandidates = emptyList(),
        ),
    )

  @Test
  fun ridesThatOverlapInTimeAreBothFlaggedForReview() {
    val vm = viewModel()
    val first = vm.simulateAmbiguousPassiveRide()!!.also { vm.keepRideAsIs(it.ride.rideId) }
    val second = first.movedBy("second", shiftMs = 60_000L)
    vm.dao.insertOrReplaceRide(second)

    assertTrue(vm.dao.flagOverlappingRides("second"))

    val a = vm.dao.getRideById(first.ride.rideId)!!.ride
    val b = vm.dao.getRideById("second")!!.ride
    assertTrue(a.needsReview && b.needsReview)
    assertEquals(listOf("second"), a.overlapsRideIds)
    assertEquals(listOf(first.ride.rideId), b.overlapsRideIds)
    assertTrue(reviewFor(b).prompt.contains("overlaps 1 other ride"), reviewFor(b).prompt)
  }

  @Test
  fun ridesThatDoNotOverlapAreLeftAlone() {
    val vm = viewModel()
    val first = vm.simulateAmbiguousPassiveRide()!!.also { vm.keepRideAsIs(it.ride.rideId) }
    // Starts exactly when the first one ends.
    val gap = first.ride.endTimeEpochMs - first.ride.startTimeEpochMs
    vm.dao.insertOrReplaceRide(first.movedBy("second", shiftMs = gap))

    assertFalse(vm.dao.flagOverlappingRides("second"))

    assertFalse(vm.dao.getRideById("second")!!.ride.needsReview)
    assertFalse(vm.dao.getRideById(first.ride.rideId)!!.ride.needsReview)
  }

  @Test
  fun keepingAnOverlappingRideClearsItsFlag() {
    val vm = viewModel()
    val first = vm.simulateAmbiguousPassiveRide()!!.also { vm.keepRideAsIs(it.ride.rideId) }
    vm.dao.insertOrReplaceRide(first.movedBy("second", shiftMs = 1_000L))
    vm.dao.flagOverlappingRides("second")

    vm.keepRideAsIs("second")

    val kept = vm.dao.getRideById("second")!!.ride
    assertFalse(kept.needsReview)
    assertTrue(kept.overlapsRideIds.isEmpty())
  }
}
