package com.straphanger.app.ui

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.capture.DynamicGeofenceWindowManager
import com.straphanger.app.data.capture.FakeGeofenceRegistrar
import com.straphanger.app.data.capture.GeofenceEvent
import com.straphanger.app.data.capture.GeofenceRegistrationException
import com.straphanger.app.data.local.RideSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StraphangerViewModelTest {

    @Test
    fun viewModelEndToEndFlowLogsRideConfirmsAmbiguousRideAndImportsTimeline() {
        val vm = StraphangerViewModel(
            backend = InMemoryPersistenceBackend(),
            dispatcher = Dispatchers.Unconfined,
            seedInitialSampleRides = true,
            networkLoader = { TestNetwork.data }
        )
        assertTrue(vm.uiState.value.isNetworkLoaded)

        // Starter rides seeded (3 rides), each resolved to at least one leg
        assertTrue(vm.uiState.value.rides.all { it.legs.isNotEmpty() })
        assertEquals(3, vm.uiState.value.stats.totalRides)

        // 1. Toggle map color mode & route filter
        vm.setMapColorMode(MapColorMode.FREQUENCY_HEATMAP)
        assertEquals(MapColorMode.FREQUENCY_HEATMAP, vm.uiState.value.mapColorMode)
        vm.setRouteFilter("4")
        assertEquals("4", vm.uiState.value.selectedRouteFilter)
        vm.setRouteFilter("4") // toggle off
        assertEquals(null, vm.uiState.value.selectedRouteFilter)

        // 2. Simulate passive ambiguous ride (14 St-Union Sq -> 86 St)
        val ambigRide = vm.simulateAmbiguousPassiveRide()!!
        assertEquals(4, vm.uiState.value.stats.totalRides)
        assertEquals(1, vm.uiState.value.stats.pendingReviewCount)
        assertTrue(ambigRide.ride.needsReview)

        // 3. Confirm ambiguous ride as 6 Local
        vm.confirmAmbiguousRide(ambigRide.ride.rideId, "6", false)
        assertEquals(0, vm.uiState.value.stats.pendingReviewCount)
        assertFalse(vm.dao.getRideById(ambigRide.ride.rideId)!!.ride.needsReview)

        // 4. Toggle stations vs complexes & Stopped-At vs Boarded/Exited
        vm.setStationGroupingMode(StationGroupingMode.STATION_COMPLEXES)
        vm.setStationVisitFilterMode(StationVisitFilterMode.BOARDED_OR_EXITED_ONLY)
        assertEquals(StationGroupingMode.STATION_COMPLEXES, vm.uiState.value.stationGroupingMode)
        assertEquals(StationVisitFilterMode.BOARDED_OR_EXITED_ONLY, vm.uiState.value.stationVisitFilterMode)

        // 5. Import sample Google Maps Timeline.json
        val imported = vm.importSampleGoogleMapsTimeline()
        assertEquals(2, imported)
        assertTrue(vm.uiState.value.stats.totalRides >= 5)
    }

    @Test
    fun submitQuickRideUsesClockTime() {
        val vm = StraphangerViewModel(
            backend = InMemoryPersistenceBackend(),
            dispatcher = Dispatchers.Unconfined,
            clock = FixedClock(Instant.parse("2026-11-11T14:00:00Z")),
            seedInitialSampleRides = false,
            networkLoader = { TestNetwork.data }
        )
        val record = vm.submitQuickRide(
            originStationId = StationIds.UNION_SQ_LEX,
            transferStationId = null,
            destinationStationId = StationIds.EIGHTY_SIXTH_LEX
        )
        assertEquals(1794405600000L, record!!.ride.startTimeEpochMs)
    }

    @Test
    fun defaultLogFormUsesGtfsStationIdsThatExistInTheNetwork() {
        val vm = StraphangerViewModel(
            backend = InMemoryPersistenceBackend(),
            dispatcher = Dispatchers.Unconfined,
            networkLoader = { TestNetwork.data }
        )
        val state = vm.uiState.value
        val engine = vm.graphEngine!!
        assertEquals("Bedford Av", engine.getStation(state.logOriginStationId)?.name)
        assertEquals("14 St-Union Sq", engine.getStation(state.logTransferStationId!!)?.name)
        assertEquals("86 St", engine.getStation(state.logDestinationStationId)?.name)
        assertTrue(vm.submitQuickRide() != null)
        assertEquals(TestNetwork.data.stationCount, vm.uiState.value.stats.totalStationsInNetwork)
    }

    @Test
    fun actionsWaitForTheNetworkToLoad() {
        val gate = CompletableDeferred<SubwayNetworkData>()
        val vm = StraphangerViewModel(
            backend = InMemoryPersistenceBackend(),
            dispatcher = Dispatchers.Unconfined,
            seedInitialSampleRides = true,
            networkLoader = { gate.await() }
        )
        assertFalse(vm.uiState.value.isNetworkLoaded)
        assertEquals(null, vm.submitQuickRide())
        assertEquals(0, vm.uiState.value.stats.totalRides)

        gate.complete(TestNetwork.data)

        assertTrue(vm.uiState.value.isNetworkLoaded)
        assertEquals(3, vm.uiState.value.stats.totalRides)
    }

    @Test
    fun failedNetworkLoadIsReportedInsteadOfLoadingForever() {
        val vm = StraphangerViewModel(
            backend = InMemoryPersistenceBackend(),
            dispatcher = Dispatchers.Unconfined,
            networkLoader = { throw IllegalStateException("file missing") }
        )
        assertFalse(vm.uiState.value.isNetworkLoaded)
        assertEquals("The subway network could not be loaded: file missing", vm.uiState.value.networkLoadError)
        assertEquals(null, vm.graphEngine)
    }

    private fun passiveVm(
        registrar: FakeGeofenceRegistrar,
        events: MutableSharedFlow<GeofenceEvent>
    ) = StraphangerViewModel(
        backend = InMemoryPersistenceBackend(),
        dispatcher = Dispatchers.Unconfined,
        clock = FixedClock(Instant.parse("2026-11-11T14:00:00Z")),
        networkLoader = { TestNetwork.data },
        geofenceRegistrar = registrar,
        geofenceEvents = events
    )

    @Test
    fun passiveCaptureIsOffUntilLocationPermissionIsGranted() {
        val registrar = FakeGeofenceRegistrar()
        val vm = passiveVm(registrar, MutableSharedFlow(extraBufferCapacity = 8))
        assertFalse(vm.uiState.value.passiveGeofenceEnabled)
        assertFalse(vm.uiState.value.googleMapsNotificationEnabled)
        assertEquals(0, vm.uiState.value.activeGeofenceCount)

        vm.updateCaptureAccess(CaptureAccessState(locationGranted = false, notificationAccessEnabled = true))
        assertTrue(registrar.replaceAllCalls.isEmpty())
        assertFalse(vm.uiState.value.passiveGeofenceEnabled)
        assertTrue(vm.uiState.value.googleMapsNotificationEnabled)
        assertEquals("Location permission is needed", vm.uiState.value.passiveCaptureError)
    }

    @Test
    fun grantedLocationRegistersGeofencesAndRecordsPassiveRides() {
        val registrar = FakeGeofenceRegistrar()
        val events = MutableSharedFlow<GeofenceEvent>(extraBufferCapacity = 8)
        val vm = passiveVm(registrar, events)

        vm.updateCaptureAccess(CaptureAccessState(locationGranted = true, lastKnownLat = 40.734673, lastKnownLng = -73.989951))
        assertEquals(1, registrar.replaceAllCalls.size)
        assertTrue(vm.uiState.value.passiveGeofenceEnabled)
        assertEquals(registrar.replaceAllCalls.single().size, vm.uiState.value.activeGeofenceCount)

        val t0 = 1794405600000L
        events.tryEmit(GeofenceEvent.Entered(DynamicGeofenceWindowManager.stationGeofenceId("602"), t0, 40.734673, -73.989951))
        events.tryEmit(GeofenceEvent.Entered(DynamicGeofenceWindowManager.stationGeofenceId("397"), t0 + 12 * 60_000L, 40.779492, -73.955589))
        val ride = vm.uiState.value.rides.single().ride
        assertEquals(RideSource.PASSIVE_GPS, ride.source)
        assertEquals(StationIds.EIGHTY_SIXTH_LEX, ride.destinationStationId)
        assertTrue(vm.uiState.value.statusMessage.startsWith("Recorded passive ride: 14 St-Union Sq → 86 St"), vm.uiState.value.statusMessage)

        // Turning passive capture off removes the geofences.
        vm.disablePassiveCapture()
        assertEquals(1, registrar.clearCalls)
        assertFalse(vm.uiState.value.passiveGeofenceEnabled)
    }

    @Test
    fun refusedRegistrationLeavesTheToggleOffAndShowsWhy() {
        val registrar = FakeGeofenceRegistrar(failure = GeofenceRegistrationException("Location permission is needed"))
        val vm = passiveVm(registrar, MutableSharedFlow(extraBufferCapacity = 8))
        vm.updateCaptureAccess(CaptureAccessState(locationGranted = true))
        assertFalse(vm.uiState.value.passiveGeofenceEnabled)
        assertEquals("Location permission is needed", vm.uiState.value.passiveCaptureError)
    }

    @Test
    fun passiveCaptureWaitsForTheNetworkToLoad() {
        val registrar = FakeGeofenceRegistrar()
        val network = CompletableDeferred<com.straphanger.app.data.gtfs.SubwayNetworkData>()
        val vm = StraphangerViewModel(
            backend = InMemoryPersistenceBackend(),
            dispatcher = Dispatchers.Unconfined,
            networkLoader = { network.await() },
            geofenceRegistrar = registrar,
            geofenceEvents = MutableSharedFlow(extraBufferCapacity = 8)
        )
        vm.updateCaptureAccess(CaptureAccessState(locationGranted = true))
        assertTrue(registrar.replaceAllCalls.isEmpty())
        network.complete(TestNetwork.data)
        assertEquals(1, registrar.replaceAllCalls.size)
        assertTrue(vm.uiState.value.passiveGeofenceEnabled)
    }
}
