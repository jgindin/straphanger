package com.straphanger.app.data.capture

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.ui.StraphangerViewModel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

class NotificationSessionTest {

  private val t0 = 1794405600000L // 2026-11-11T14:00:00Z, a weekday
  private val minute = 60_000L
  private val navText = "GMAPS_NOTIFICATION: 4 Train | Board at 14 St-Union Sq • Exit at 86 St"
  private val otherNavText = "GMAPS_NOTIFICATION: L Train | Board at Bedford Av • Exit at 1 Av"

  @Test
  fun tenPostsOfOneNotificationThenRemovalAreOneSession() {
    val store = InMemoryNotificationSessionStore()
    val tracker = NotificationSessionTracker(store)
    repeat(10) { i -> assertFalse(tracker.onPosted("k1", t0 + i * minute, navText)) }
    assertTrue(tracker.onRemoved("k1", t0 + 14 * minute))

    val session = store.read().completed.single()
    assertEquals(t0, session.firstPostMs)
    assertEquals(t0 + 14 * minute, session.endMs)
    assertNull(store.read().open)
  }

  @Test
  fun aNewKeyEndsTheOpenSession() {
    val store = InMemoryNotificationSessionStore()
    val tracker = NotificationSessionTracker(store)
    tracker.onPosted("k1", t0, navText)
    tracker.onPosted("k1", t0 + 5 * minute, navText)
    assertTrue(tracker.onPosted("k2", t0 + 30 * minute, otherNavText))

    val ended = store.read().completed.single()
    assertEquals("k1", ended.key)
    assertEquals(t0 + 5 * minute, ended.endMs)
    assertEquals("k2", store.read().open?.key)
  }

  @Test
  fun removalOfAnotherNotificationLeavesTheSessionOpen() {
    val store = InMemoryNotificationSessionStore()
    val tracker = NotificationSessionTracker(store)
    tracker.onPosted("k1", t0, navText)
    assertFalse(tracker.onRemoved("other", t0 + minute))
    assertEquals("k1", store.read().open?.key)
  }

  @Test
  fun theOpenSessionSurvivesInTheFile() {
    val dir = Path(SystemTemporaryDirectory, "straphanger-test-${Random.nextLong().toULong()}")
    val file = Path(dir, "notification_sessions.json")
    NotificationSessionTracker(FileNotificationSessionStore(file)).onPosted("k1", t0, navText)

    // A new process: a new store reading the same file.
    val tracker = NotificationSessionTracker(FileNotificationSessionStore(file))
    tracker.onRemoved("k1", t0 + 12 * minute)

    val session = FileNotificationSessionStore(file).read().completed.single()
    assertEquals(t0, session.firstPostMs)
    assertEquals(t0 + 12 * minute, session.endMs)
    SystemFileSystem.delete(file)
  }

  private fun viewModel(store: NotificationSessionStore, signal: MutableStateFlow<Int>) =
    StraphangerViewModel(
      backend = InMemoryPersistenceBackend(),
      dispatcher = Dispatchers.Unconfined,
      networkLoader = { TestNetwork.data },
      notificationSessions = store,
      notificationSessionsEnded = signal,
    )

  @Test
  fun oneSessionRecordsOneRideWithTheSessionTimes() {
    val store = InMemoryNotificationSessionStore()
    val signal = MutableStateFlow(0)
    val vm = viewModel(store, signal)
    val tracker = NotificationSessionTracker(store)
    repeat(10) { i -> tracker.onPosted("k1", t0 + i * minute, navText) }
    tracker.onRemoved("k1", t0 + 14 * minute)
    signal.value++

    val ride = vm.dao.getAllRides().single().ride
    assertEquals(RideSource.GMAPS_NOTIFICATION, ride.source)
    assertEquals("635", ride.originStationId)
    assertEquals("626", ride.destinationStationId)
    assertEquals(t0, ride.startTimeEpochMs)
    assertEquals(t0 + 14 * minute, ride.endTimeEpochMs)
    assertTrue(store.read().completed.isEmpty())
  }

  @Test
  fun twoSessionsRecordTwoRides() {
    val store = InMemoryNotificationSessionStore()
    val signal = MutableStateFlow(0)
    val vm = viewModel(store, signal)
    val tracker = NotificationSessionTracker(store)
    tracker.onPosted("k1", t0, navText)
    tracker.onRemoved("k1", t0 + 14 * minute)
    signal.value++
    tracker.onPosted("k2", t0 + 3 * 60 * minute, navText)
    tracker.onRemoved("k2", t0 + 3 * 60 * minute + 15 * minute)
    signal.value++

    assertEquals(2, vm.dao.getAllRides().size)
  }

  @Test
  fun aDuplicateSessionIsSkipped() {
    val first =
      NavigationNotificationSession("k1", t0, t0, navText, navText, endMs = t0 + 14 * minute)
    // The same trip seen again a minute later, e.g. the notification re-posted under a new key.
    val again =
      NavigationNotificationSession(
        "k2",
        t0 + minute,
        t0 + minute,
        navText,
        navText,
        endMs = t0 + 15 * minute,
      )
    val store =
      InMemoryNotificationSessionStore(NotificationSessionState(completed = listOf(first, again)))

    // Sessions that ended while no view model ran are drained when it starts.
    val vm = viewModel(store, MutableStateFlow(0))

    assertEquals(1, vm.dao.getAllRides().size)
    assertTrue(store.read().completed.isEmpty())
  }
}
