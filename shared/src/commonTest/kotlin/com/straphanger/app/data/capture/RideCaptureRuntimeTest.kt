package com.straphanger.app.data.capture

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.testutil.TestBasemap
import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.StationIds
import com.straphanger.app.ui.StraphangerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class RideCaptureRuntimeTest {

  private val t0 = 1794405600000L // 2026-11-11T14:00:00Z, a weekday afternoon
  private val minute = 60_000L
  private val navText = "GMAPS_NOTIFICATION: 4 Train | Board at 14 St-Union Sq • Exit at 86 St"

  private class RecordingNotifier : RideNotifier {
    val posted = mutableListOf<Pair<String, RideNotificationText>>()

    override fun rideRecorded(rideId: String, text: RideNotificationText) {
      posted += rideId to text
    }
  }

  private val notifier = RecordingNotifier()

  private fun runtime(
    pending: PendingGeofenceEventStore = InMemoryPendingGeofenceEventStore(),
    nowMs: Long = t0 + 30 * minute,
    backend: RidePersistenceBackend = InMemoryPersistenceBackend(),
    sessions: NotificationSessionStore = InMemoryNotificationSessionStore(),
    clock: FixedClock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
    idleCloseScheduler: IdleCloseScheduler = IdleCloseScheduler.None,
  ) =
    RideCaptureRuntime(
      backend = backend,
      clock = clock,
      dispatcher = Dispatchers.Unconfined,
      networkLoader = { TestNetwork.data },
      registrar = FakeGeofenceRegistrar(),
      geofenceEvents = MutableSharedFlow(),
      pendingEvents = pending,
      notificationSessions = sessions,
      notificationSessionsEnded = MutableStateFlow(0),
      notifier = notifier,
      idleCloseScheduler = idleCloseScheduler,
    )

  private fun station(complexId: String) = DynamicGeofenceWindowManager.stationGeofenceId(complexId)

  /** Stored entries for a ride from Union Sq (602) to 86 St (397) starting at [startMs]. */
  private fun storedRide(startMs: Long) =
    InMemoryPendingGeofenceEventStore().apply {
      append(GeofenceEvent.Exited(station("602"), startMs, 40.734673, -73.989951))
      append(GeofenceEvent.Entered(station("397"), startMs + 10 * minute, 40.779492, -73.955589))
    }

  @Test
  fun catchUpTurnsStoredStationEventsIntoARideAndNotifiesWithNoViewModel() = runTest {
    val runtime = runtime(storedRide(t0))

    runtime.catchUp()
    // A second check, e.g. a late wake-up, records and notifies nothing more.
    runtime.catchUp()

    val ride = runtime.dao.getAllRides().single().ride
    assertEquals(RideSource.PASSIVE_GPS, ride.source)
    val (id, text) = notifier.posted.single()
    assertEquals(ride.rideId, id)
    assertTrue(listOf("4 · ", "5 · ", "6 · ").any { text.title.startsWith(it) }, text.title)
    assertTrue("→" in text.title, text.title)
  }

  @Test
  fun aBacklogOfTwoRidesNotifiesTwice() = runTest {
    val store = storedRide(t0)
    // Back from 86 St three hours later.
    store.append(GeofenceEvent.Exited(station("397"), t0 + 180 * minute, 40.779492, -73.955589))
    store.append(GeofenceEvent.Entered(station("602"), t0 + 192 * minute, 40.734673, -73.989951))
    val runtime = runtime(store, nowMs = t0 + 230 * minute)

    runtime.catchUp()

    assertEquals(2, runtime.dao.getAllRides().size)
    assertEquals(
      runtime.dao.getAllRides().map { it.ride.rideId }.toSet(),
      notifier.posted.map { it.first }.toSet(),
    )
    assertEquals(2, notifier.posted.size)
  }

  @Test
  fun aFailedSaveDoesNotNotifyAndTheReplayDoes() = runTest {
    var failing = true
    val inner = InMemoryPersistenceBackend()
    val backend =
      object : RidePersistenceBackend {
        override fun loadSnapshotJson() = inner.loadSnapshotJson()

        override fun saveSnapshotJson(json: String) {
          if (failing) throw IllegalStateException("disk full")
          inner.saveSnapshotJson(json)
        }
      }
    val runtime = runtime(storedRide(t0), backend = backend)

    runtime.catchUp()
    assertTrue(notifier.posted.isEmpty())
    assertTrue(runtime.dao.getAllRides().isEmpty())

    failing = false
    runtime.catchUp()
    assertEquals(1, notifier.posted.size)
    assertEquals(runtime.dao.getAllRides().single().ride.rideId, notifier.posted.single().first)

    // Once saved, a later check does not record or notify the ride again.
    runtime.catchUp()
    assertEquals(1, notifier.posted.size)
    assertEquals(1, runtime.dao.getAllRides().size)
  }

  @Test
  fun endedNavigationSessionIsRecordedAndNotified() = runTest {
    val session =
      NavigationNotificationSession("k1", t0, t0, navText, navText, endMs = t0 + 14 * minute)
    val sessions =
      InMemoryNotificationSessionStore(NotificationSessionState(completed = listOf(session)))
    val runtime = runtime(sessions = sessions)

    runtime.catchUp()

    val ride = runtime.dao.getAllRides().single().ride
    assertEquals(RideSource.GMAPS_NOTIFICATION, ride.source)
    assertEquals(ride.rideId, notifier.posted.single().first)
    assertTrue(sessions.read().completed.isEmpty())
  }

  @Test
  fun aDuplicateNavigationSessionIsNotNotified() = runTest {
    val first =
      NavigationNotificationSession("k1", t0, t0, navText, navText, endMs = t0 + 14 * minute)
    // The same trip seen again a minute later, under a new key.
    val again =
      NavigationNotificationSession(
        "k2",
        t0 + minute,
        t0 + minute,
        navText,
        navText,
        endMs = t0 + 15 * minute,
      )
    val sessions =
      InMemoryNotificationSessionStore(NotificationSessionState(completed = listOf(first, again)))
    val runtime = runtime(sessions = sessions)

    runtime.catchUp()

    assertEquals(1, runtime.dao.getAllRides().size)
    assertEquals(1, notifier.posted.size)
  }

  @Test
  fun viewModelAndBackgroundShareOneDao() = runTest {
    val runtime = runtime(storedRide(t0))
    val vm = viewModel(runtime)
    assertSame(runtime.dao, vm.dao)

    runtime.catchUp()

    val ride = vm.uiState.value.rides.single().ride
    assertEquals(RideSource.PASSIVE_GPS, ride.source)
    assertTrue(vm.uiState.value.statusMessage.startsWith("Recorded passive ride: "))
    assertEquals(1, notifier.posted.size)
  }

  @Test
  fun manualRidesAreNotNotified() = runTest {
    val runtime = runtime()
    val vm = viewModel(runtime)

    val record =
      vm.submitQuickRide(
        originStationId = StationIds.UNION_SQ_LEX,
        transferStationId = null,
        destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
      )

    assertNotNull(record)
    assertEquals(1, runtime.dao.getAllRides().size)
    assertTrue(notifier.posted.isEmpty())
  }

  @Test
  fun catchUpInAFreshRuntimeRecordsOnceAndTheAppDoesNotRepeatIt() = runTest {
    // One rides file and one pending store, as a fresh process would find them.
    val backend = InMemoryPersistenceBackend()
    val pending = storedRide(t0)
    val alarm = RecordingIdleCloseScheduler()

    // The first process handles the entries before the chain goes idle, then dies.
    val first =
      runtime(pending, nowMs = t0 + 11 * minute, backend = backend, idleCloseScheduler = alarm)
    first.catchUp()
    assertTrue(first.dao.getAllRides().isEmpty())
    assertEquals(t0 + 10 * minute + PassiveRideReconstructor.DEFAULT_IDLE_CLOSE_MS, alarm.current)
    first.scope.cancel()

    // The alarm wakes a fresh process after the idle period.
    val second =
      runtime(pending, nowMs = t0 + 30 * minute, backend = backend, idleCloseScheduler = alarm)
    second.catchUp()
    assertEquals(1, second.dao.getAllRides().size)
    assertEquals(1, notifier.posted.size)
    assertNull(alarm.current)

    // The user then opens the app and capture starts: nothing is recorded or notified again.
    val vm = viewModel(second)
    vm.updateCaptureAccess(
      CaptureAccessState(locationGranted = true, lastKnownLat = 40.73, lastKnownLng = -73.99),
    )
    assertTrue(vm.uiState.value.passiveGeofenceEnabled)
    assertEquals(1, vm.uiState.value.rides.size)
    assertEquals(1, second.dao.getAllRides().size)
    assertEquals(1, notifier.posted.size)
  }

  @Test
  fun catchUpTwiceAfterIdleNotifiesOnce() = runTest {
    val clock = FixedClock(Instant.fromEpochMilliseconds(t0 + 11 * minute))
    val alarm = RecordingIdleCloseScheduler()
    val runtime = runtime(storedRide(t0), clock = clock, idleCloseScheduler = alarm)
    runtime.catchUp()
    assertEquals(t0 + 10 * minute + PassiveRideReconstructor.DEFAULT_IDLE_CLOSE_MS, alarm.current)

    // The alarm fires, and a second wake-up follows it.
    clock.instant = Instant.fromEpochMilliseconds(t0 + 26 * minute)
    runtime.catchUp()
    runtime.catchUp()

    assertEquals(runtime.dao.getAllRides().single().ride.rideId, notifier.posted.single().first)
    assertNull(alarm.current)
  }

  private fun viewModel(runtime: RideCaptureRuntime) =
    StraphangerViewModel(
      dispatcher = Dispatchers.Unconfined,
      basemapLoader = { TestBasemap.empty },
      runtime = runtime,
    )
}
