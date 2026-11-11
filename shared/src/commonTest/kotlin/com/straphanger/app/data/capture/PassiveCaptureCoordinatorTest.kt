package com.straphanger.app.data.capture

import com.straphanger.app.data.capture.DynamicGeofenceWindowManager.Companion.MACRO_ANCHOR_ID
import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.testutil.TestNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

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

/** A clock that reads the test scheduler's virtual time, starting at [baseMs]. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val baseMs: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(baseMs + scheduler.currentTime)
}

@OptIn(ExperimentalCoroutinesApi::class)
class PassiveCaptureCoordinatorTest {

    // 14 St-Union Sq (Lexington Av platform)
    private val unionSqLat = 40.734673
    private val unionSqLng = -73.989951
    private val t0 = 1794405600000L // 2026-11-11T14:00:00Z, a weekday afternoon
    private val minute = 60_000L

    private class Harness(
        scope: CoroutineScope,
        clock: Clock,
        val store: PendingGeofenceEventStore,
        backend: RidePersistenceBackend = InMemoryPersistenceBackend()
    ) {
        val engine = TestNetwork.engine()
        val dao = SubwayDao(backend)
        val windowManager = DynamicGeofenceWindowManager(engine)
        val reconstructor = PassiveRideReconstructor(engine, dao)
        val registrar = FakeGeofenceRegistrar()
        val events = MutableSharedFlow<GeofenceEvent>()
        val coordinator = PassiveCaptureCoordinator(
            windowManager, reconstructor, registrar, events, scope, clock = clock, pendingEvents = store
        )
    }

    private fun TestScope.harness(
        store: PendingGeofenceEventStore = InMemoryPendingGeofenceEventStore(),
        backend: RidePersistenceBackend = InMemoryPersistenceBackend()
    ) = Harness(backgroundScope, SchedulerClock(testScheduler, t0), store, backend)

    private fun TestScope.startedHarness(): Harness {
        val h = harness()
        h.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()
        return h
    }

    private fun stationGeofence(complexId: String) = DynamicGeofenceWindowManager.stationGeofenceId(complexId)

    private fun entered(complexId: String, atMinute: Double): GeofenceEvent.Entered {
        val st = TestNetwork.engine().getStationsInComplex(complexId).first()
        return GeofenceEvent.Entered(stationGeofence(complexId), t0 + (atMinute * minute).toLong(), st.lat, st.lng)
    }

    /** Advances virtual time to the event's timestamp, then delivers it as the receiver would. */
    private suspend fun TestScope.deliver(h: Harness, event: GeofenceEvent) {
        val waitMs = event.epochMs - t0 - testScheduler.currentTime
        if (waitMs > 0) advanceTimeBy(waitMs)
        runCurrent()
        if (event is GeofenceEvent.Entered) h.store.append(event)
        h.events.emit(event)
        runCurrent()
    }

    private fun TestScope.silence(minutes: Long) {
        advanceTimeBy(minutes * minute)
        runCurrent()
    }

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

        deliver(h, GeofenceEvent.Exited(MACRO_ANCHOR_ID, t0, newLat, newLng))

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
        deliver(h, entered("602", 0.0))
        deliver(h, GeofenceEvent.Exited(stationGeofence("602"), t0 + minute, unionSqLat, unionSqLng))
        assertEquals(1, h.registrar.replaceAllCalls.size)
        assertEquals("602", h.reconstructor.chainOriginComplexId)
    }

    @Test
    fun subwaySpeedTripRecordsRide() = runTest {
        val h = startedHarness()
        // 14 St-Union Sq (602) to 86 St (397): about 3.5 miles in 12 minutes.
        deliver(h, entered("602", 0.0))
        deliver(h, entered("397", 12.0))
        // The chain is still open until 15 minutes pass with no new station.
        assertEquals(0, h.dao.getAllRides().size)
        silence(14)
        assertEquals(0, h.dao.getAllRides().size)
        silence(1)

        val rides = h.dao.getAllRides()
        assertEquals(1, rides.size)
        val ride = rides.single().ride
        assertEquals(RideSource.PASSIVE_GPS, ride.source)
        assertEquals("635", ride.originStationId)
        assertEquals("626", ride.destinationStationId)
        assertEquals(t0, ride.startTimeEpochMs)
        assertEquals(t0 + 12 * minute, ride.endTimeEpochMs)
        assertNull(h.reconstructor.chainOriginComplexId)
        assertEquals(1, h.coordinator.status.value.ridesRecorded)
    }

    @Test
    fun walkingBetweenNearbyStationsRecordsNothing() = runTest {
        val h = startedHarness()
        // 14 St-Union Sq (602) to 23 St-Baruch College (405): about 0.4 miles in 9 minutes (2.7 mph).
        deliver(h, entered("602", 0.0))
        deliver(h, entered("405", 9.0))

        assertEquals(0, h.dao.getAllRides().size)
        assertEquals("405", h.reconstructor.chainOriginComplexId)
        silence(30)
        assertEquals(0, h.dao.getAllRides().size)
    }

    @Test
    fun walkingBetweenFacingGeofenceEdgesRecordsNothing() = runTest {
        val h = startedHarness()
        // 14 St-Union Sq (602) and 23 St-Baruch College (405) are about 0.41 miles apart centre to centre.
        // A walker is detected at the facing edges of their 160 m geofences, so covers only about 0.21
        // miles; 4 minutes between entries reads as 6.2 mph centre to centre.
        val north = 160.0 / 111_320.0 // 160 m of latitude
        deliver(h, GeofenceEvent.Entered(stationGeofence("602"), t0, 40.734789 + north, -73.99073))
        deliver(h, GeofenceEvent.Entered(stationGeofence("405"), t0 + 4 * minute, 40.739864 - north, -73.986599))
        assertEquals("405", h.reconstructor.chainLastComplexId) // the hop check still extends the chain
        silence(15)
        assertEquals(0, h.dao.getAllRides().size)
    }

    @Test
    fun twoStopRideStillRecorded() = runTest {
        val h = startedHarness()
        // 14 St-Union Sq (602) to 28 St (404), two stops on the 6: about 0.67 miles in 3 minutes.
        deliver(h, entered("602", 0.0))
        deliver(h, entered("404", 3.0))
        silence(15)
        val ride = h.dao.getAllRides().single().ride
        assertEquals("635", ride.originStationId)
        assertEquals("633", ride.destinationStationId)
    }

    @Test
    fun continuousGpsAlongLineRecordsOneRide() = runTest {
        val h = startedHarness()
        // GPS works between stations: every Lexington Av stop from 14 St to Grand Central, 1.5 minutes apart.
        listOf("602", "405", "404", "403", "610").forEachIndexed { i, complex ->
            deliver(h, entered(complex, i * 1.5))
        }
        assertEquals(0, h.dao.getAllRides().size)
        assertEquals("602", h.reconstructor.chainOriginComplexId)
        assertEquals("610", h.reconstructor.chainLastComplexId)
        silence(15)

        val ride = h.dao.getAllRides().single().ride
        assertEquals("635", ride.originStationId)
        assertEquals("631", ride.destinationStationId)
        assertEquals(t0, ride.startTimeEpochMs)
        assertEquals(t0 + 6 * minute, ride.endTimeEpochMs)
    }

    @Test
    fun walkThenRideRecordsOnlyTheRide() = runTest {
        val h = startedHarness()
        // Walk from 23 St (405) to 14 St-Union Sq (602) in 9 minutes, then ride to 86 St (397) in 12.
        deliver(h, entered("405", 0.0))
        deliver(h, entered("602", 9.0))
        deliver(h, entered("397", 21.0))
        silence(15)

        val ride = h.dao.getAllRides().single().ride
        assertEquals("635", ride.originStationId)
        assertEquals("626", ride.destinationStationId)
        assertEquals(t0 + 9 * minute, ride.startTimeEpochMs)
    }

    @Test
    fun tripLongerThan120MinutesRecordsNothing() = runTest {
        val h = startedHarness()
        // Fast hops keep the chain alive, but the whole trip is too long to be one ride.
        var at = 0.0
        repeat(12) {
            deliver(h, entered("602", at)); at += 10.0
            deliver(h, entered("397", at)); at += 10.0
        }
        silence(15)
        assertEquals(0, h.dao.getAllRides().size)
    }

    @Test
    fun tripShorterThan2MinutesRecordsNothing() = runTest {
        val h = startedHarness()
        deliver(h, entered("602", 0.0))
        deliver(h, entered("405", 1.5))
        silence(15)
        assertEquals(0, h.dao.getAllRides().size)
        assertNull(h.reconstructor.chainOriginComplexId)
    }

    @Test
    fun pendingEventsAreReplayedAtStartAndRecordTheRide() = runTest {
        // Recorded by the receiver while no view model was running.
        val store = InMemoryPendingGeofenceEventStore()
        store.append(entered("397", 12.0))
        store.append(entered("602", 0.0))
        val h = harness(store)
        advanceTimeBy(40 * minute)

        h.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()

        val ride = h.dao.getAllRides().single().ride
        assertEquals("635", ride.originStationId)
        assertEquals("626", ride.destinationStationId)
        assertTrue(store.readAll().isEmpty())
    }

    @Test
    fun liveEventsAlsoInThePendingFileAreHandledOnce() = runTest {
        val h = startedHarness()
        deliver(h, entered("602", 0.0))
        // The same entry delivered again, e.g. a second wake-up, does not restart the chain.
        h.events.emit(entered("602", 0.0))
        runCurrent()
        deliver(h, entered("397", 12.0))
        silence(15)
        assertEquals(1, h.dao.getAllRides().size)
        assertTrue(h.store.readAll().isEmpty())
    }

    @Test
    fun openChainSurvivesARestart() = runTest {
        val store = InMemoryPendingGeofenceEventStore()
        val first = harness(store)
        first.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()
        deliver(first, entered("602", 0.0))
        first.coordinator.stop()
        runCurrent()
        // The open chain's entries stay in the file.
        assertEquals(1, store.readAll().size)

        // The process restarts; the receiver records the arrival with no view model running.
        advanceTimeBy(12 * minute)
        store.append(entered("397", 12.0))
        val second = harness(store)
        second.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()
        silence(15)
        assertEquals(1, second.dao.getAllRides().size)
    }

    @Test
    fun failedSaveKeepsTheChainEntriesForTheNextStart() = runTest {
        val store = InMemoryPendingGeofenceEventStore()
        val failing = object : RidePersistenceBackend {
            override fun loadSnapshotJson(): String? = null
            override fun saveSnapshotJson(json: String) = throw IllegalStateException("disk full")
        }
        val first = harness(store, failing)
        first.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()
        deliver(first, entered("602", 0.0))
        deliver(first, entered("397", 12.0))
        silence(15)
        assertEquals(0, first.dao.getAllRides().size)
        // The ride could not be saved, so its entries stay for a retry.
        assertEquals(2, store.readAll().size)
        first.coordinator.stop()
        runCurrent()

        // The next start, with storage working again, records the ride.
        val second = harness(store)
        second.coordinator.start(unionSqLat, unionSqLng)
        runCurrent()
        val ride = second.dao.getAllRides().single().ride
        assertEquals("635", ride.originStationId)
        assertEquals("626", ride.destinationStationId)
        assertTrue(store.readAll().isEmpty())
    }

    @Test
    fun registrationFailureIsReportedAndDoesNotStopEventHandling() = runTest {
        val h = harness()
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
