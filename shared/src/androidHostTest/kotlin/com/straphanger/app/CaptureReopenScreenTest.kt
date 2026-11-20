package com.straphanger.app

import com.straphanger.app.data.capture.FakeGeofenceRegistrar
import com.straphanger.app.data.capture.InMemoryNotificationSessionStore
import com.straphanger.app.data.capture.InMemoryPendingGeofenceEventStore
import com.straphanger.app.data.capture.RideCaptureRuntime
import com.straphanger.app.data.capture.RideNotifier
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.testutil.BundledBasemap
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.StraphangerViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

/** The app reopened while its process, and so its running capture, is still alive. */
class CaptureReopenScreenTest : AppScreenTest() {

  private val registrar = FakeGeofenceRegistrar()

  private val runtime =
    RideCaptureRuntime(
      backend = InMemoryPersistenceBackend(),
      clock = FixedClock(Instant.parse("2026-11-11T14:00:00Z")),
      dispatcher = Dispatchers.Unconfined,
      networkLoader = { BundledNetwork.data },
      registrar = registrar,
      geofenceEvents = MutableSharedFlow(),
      pendingEvents = InMemoryPendingGeofenceEventStore(),
      notificationSessions = InMemoryNotificationSessionStore(),
      notificationSessionsEnded = MutableStateFlow(0),
      notifier = RideNotifier.None,
    )

  private val granted =
    CaptureAccessState(locationGranted = true, lastKnownLat = 40.73, lastKnownLng = -73.99)

  @After
  fun cancelRuntime() {
    runtime.scope.cancel()
  }

  private fun runtimeViewModel() =
    StraphangerViewModel(
        dispatcher = Dispatchers.Unconfined,
        basemapLoader = { BundledBasemap.data },
        runtime = runtime,
      )
      .also { viewModels += it }

  @Test
  fun reopeningBeforeAccessIsReadKeepsCaptureRunning() {
    runtimeViewModel().updateCaptureAccess(granted)
    val coordinator = checkNotNull(runtime.coordinator)
    assertTrue(coordinator.status.value.running)

    // The activity has not read access yet when the new app first composes.
    captureAccess = null
    launchApp(runtimeViewModel())
    composeRule.waitForIdle()
    assertEquals(0, registrar.clearCalls)
    assertTrue(coordinator.status.value.running)

    captureAccess = granted
    composeRule.waitForIdle()
    assertEquals(0, registrar.clearCalls)
    assertTrue(coordinator.status.value.running)
  }
}
