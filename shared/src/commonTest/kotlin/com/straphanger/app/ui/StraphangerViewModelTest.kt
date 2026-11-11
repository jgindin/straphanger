package com.straphanger.app.ui

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StraphangerViewModelTest {

    @Test
    fun viewModelEndToEndFlowLogsRideConfirmsAmbiguousRideAndImportsTimeline() {
        val vm = StraphangerViewModel(
            backend = InMemoryPersistenceBackend(),
            seedInitialSampleRides = true
        )

        // Starter rides seeded (3 rides)
        assertEquals(3, vm.uiState.value.stats.totalRides)

        // 1. Toggle map color mode & route filter
        vm.setMapColorMode(MapColorMode.FREQUENCY_HEATMAP)
        assertEquals(MapColorMode.FREQUENCY_HEATMAP, vm.uiState.value.mapColorMode)
        vm.setRouteFilter("4")
        assertEquals("4", vm.uiState.value.selectedRouteFilter)
        vm.setRouteFilter("4") // toggle off
        assertEquals(null, vm.uiState.value.selectedRouteFilter)

        // 2. Simulate passive ambiguous ride (14 St-Union Sq -> 86 St)
        val ambigRide = vm.simulateAmbiguousPassiveRide()
        assertEquals(4, vm.uiState.value.stats.totalRides)
        assertEquals(1, vm.uiState.value.stats.pendingReviewCount)
        assertTrue(ambigRide.ride.needsReview)

        // 3. Confirm ambiguous ride as 6 Local
        vm.confirmAmbiguousRide(ambigRide.ride.rideId, "6", false)
        assertEquals(0, vm.uiState.value.stats.pendingReviewCount)
        assertFalse(vm.dao.getRideById(ambigRide.ride.rideId)!!.ride.needsReview)

        // 4. Toggle 472 Stations vs 424 Complexes & Stopped-At vs Boarded/Exited
        vm.setStationGroupingMode(StationGroupingMode.COMPLEXES_424)
        vm.setStationVisitFilterMode(StationVisitFilterMode.BOARDED_OR_EXITED_ONLY)
        assertEquals(StationGroupingMode.COMPLEXES_424, vm.uiState.value.stationGroupingMode)
        assertEquals(StationVisitFilterMode.BOARDED_OR_EXITED_ONLY, vm.uiState.value.stationVisitFilterMode)

        // 5. Import sample Google Maps Timeline.json
        val imported = vm.importSampleGoogleMapsTimeline()
        assertTrue(imported >= 1)
        assertTrue(vm.uiState.value.stats.totalRides >= 5)
    }
}
