package com.straphanger.app.data.capture

import com.straphanger.app.data.capture.DynamicGeofenceWindowManager.Companion.MACRO_ANCHOR_ID
import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.testutil.TestNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeGeofenceRegistrar(var failure: Exception? = null) : GeofenceRegistrar {
    val replaceAllCalls = mutableListOf<List<ActiveGeofenceSpec>>()
    var clearCalls = 0

    override suspend fun replaceAll(specs: List<ActiveGeofenceSpec>) {
        failure?.let { throw it }
        replaceAllCalls += specs
    }

    override suspend fun clear() {
        clearCalls++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PassiveCaptureCoordinatorTest {

    // 14 St-Union Sq (Lexington Av platform)
    private val unionSqLat = 40.734673
    private val unionSqLng = -73.989951
    private val t0 = 1794405600000L // 2026-11-11T14:00:00Z, a weekday afternoon

    private class Harness(scope: CoroutineScope) {
        val engine = TestNetwork.engine()
        val dao = SubwayDao(InMemoryPersistenceBackend())
        val windowManager = DynamicGeofenceWindowManager(engine)
        val reconstructor = PassiveRideReconstructor(engine, dao)
        val registrar = FakeGeofenceRegistrar()
        val events = MutableSharedFlow<GeofenceEvent>()
        val coordinator = PassiveCaptureCoordinator(windowManager, reconstructor, registrar, events, scope)
    }

    private fun TestScope.startedHarness(): Harness {
        val h = Harness(backgroundScope)
        h.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()
        return h
    }

    private fun stationGeofence(complexId: String) = DynamicGeofenceWindowManager.stationGeofenceId(complexId)

    @Test
    fun startRegistersAtMost19Regions() = runTest {
        val h = startedHarness()
        assertEquals(1, h.registrar.replaceAllCalls.size)
        val specs = h.registrar.replaceAllCalls.single()
        assertTrue(specs.size <= 19, "registered ${specs.size} regions")
        assertTrue(specs.any { it.geofenceId == MACRO_ANCHOR_ID })
        assertEquals(specs.size, h.coordinator.status.value.registeredCount)
        assertNull(h.coordinator.status.value.error)
    }

    @Test
    fun exitingMacroAnchorRefreshesWindowAroundNewLocation() = runTest {
        val h = startedHarness()
        // About 2 km north-east of Union Sq, next to Grand Central-42 St.
        val newLat = 40.7520
        val newLng = -73.9770
        assertTrue(NycSubwayNetworkCatalog.haversineMiles(unionSqLat, unionSqLng, newLat, newLng) * 1.609 > 1.9)

        h.events.emit(GeofenceEvent.Exited(MACRO_ANCHOR_ID, t0, newLat, newLng))
        runCurrent()

        assertEquals(2, h.registrar.replaceAllCalls.size)
        val refreshed = h.registrar.replaceAllCalls[1]
        val expectedNearest = h.engine.network.stations
            .minBy { NycSubwayNetworkCatalog.haversineMiles(newLat, newLng, it.lat, it.lng) }
            .complexId
        assertEquals("610", expectedNearest)
        assertEquals(expectedNearest, refreshed.first { !it.isMacroAnchor }.complexId)
        val anchor = refreshed.single { it.isMacroAnchor }
        assertEquals(newLat, anchor.lat)
        assertEquals(newLng, anchor.lng)
    }

    @Test
    fun exitingAStationGeofenceDoesNothing() = runTest {
        val h = startedHarness()
        h.events.emit(GeofenceEvent.Entered(stationGeofence("602"), t0, unionSqLat, unionSqLng))
        h.events.emit(GeofenceEvent.Exited(stationGeofence("602"), t0 + 60_000L, unionSqLat, unionSqLng))
        runCurrent()
        assertEquals(1, h.registrar.replaceAllCalls.size)
        assertEquals("602", h.reconstructor.activeCandidateComplexId)
    }

    @Test
    fun subwaySpeedTripRecordsRide() = runTest {
        val h = startedHarness()
        // 14 St-Union Sq (602) to 86 St (397): about 3.5 miles in 12 minutes.
        h.events.emit(GeofenceEvent.Entered(stationGeofence("602"), t0, unionSqLat, unionSqLng))
        h.events.emit(GeofenceEvent.Entered(stationGeofence("397"), t0 + 12 * 60_000L, 40.779492, -73.955589))
        runCurrent()

        val rides = h.dao.getAllRides()
        assertEquals(1, rides.size)
        val ride = rides.single().ride
        assertEquals(RideSource.PASSIVE_GPS, ride.source)
        assertEquals("602", h.engine.getStation(ride.originStationId)?.complexId)
        assertEquals("626", ride.destinationStationId)
        assertEquals(t0, ride.startTimeEpochMs)
        assertEquals(t0 + 12 * 60_000L, ride.endTimeEpochMs)
        assertNull(h.reconstructor.activeCandidateComplexId)
        assertEquals(1, h.coordinator.status.value.ridesRecorded)
    }

    @Test
    fun walkingBetweenNearbyStationsRecordsNothing() = runTest {
        val h = startedHarness()
        // 14 St-Union Sq (602) to 23 St-Baruch College (405): about 0.4 miles in 9 minutes (2.7 mph).
        h.events.emit(GeofenceEvent.Entered(stationGeofence("602"), t0, unionSqLat, unionSqLng))
        h.events.emit(GeofenceEvent.Entered(stationGeofence("405"), t0 + 9 * 60_000L, 40.739864, -73.986599))
        runCurrent()

        assertEquals(0, h.dao.getAllRides().size)
        assertEquals("405", h.reconstructor.activeCandidateComplexId)
    }

    @Test
    fun tripLongerThan120MinutesRecordsNothing() = runTest {
        val h = startedHarness()
        h.events.emit(GeofenceEvent.Entered(stationGeofence("602"), t0, unionSqLat, unionSqLng))
        h.events.emit(GeofenceEvent.Entered(stationGeofence("397"), t0 + 121 * 60_000L, 40.779492, -73.955589))
        runCurrent()
        assertEquals(0, h.dao.getAllRides().size)
        assertEquals("397", h.reconstructor.activeCandidateComplexId)
    }

    @Test
    fun tripShorterThan2MinutesRecordsNothing() = runTest {
        val h = startedHarness()
        h.events.emit(GeofenceEvent.Entered(stationGeofence("602"), t0, unionSqLat, unionSqLng))
        h.events.emit(GeofenceEvent.Entered(stationGeofence("405"), t0 + 90_000L, 40.739864, -73.986599))
        runCurrent()
        assertEquals(0, h.dao.getAllRides().size)
        assertEquals("405", h.reconstructor.activeCandidateComplexId)
    }

    @Test
    fun registrationFailureIsReportedAndDoesNotStopEventHandling() = runTest {
        val h = Harness(backgroundScope)
        h.registrar.failure = GeofenceRegistrationException("Location permission is needed")
        h.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()

        assertEquals("Location permission is needed", h.coordinator.status.value.error)
        assertEquals(0, h.coordinator.status.value.registeredCount)

        // Retrying once permission is granted registers the window.
        h.registrar.failure = null
        h.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()
        assertEquals(1, h.registrar.replaceAllCalls.size)
        assertNull(h.coordinator.status.value.error)
    }

    @Test
    fun stopClearsGeofences() = runTest {
        val h = startedHarness()
        h.coordinator.stop()
        runCurrent()
        assertEquals(1, h.registrar.clearCalls)
        assertEquals(0, h.coordinator.status.value.registeredCount)
    }
}
