package com.straphanger.app.data.capture

import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PassiveCaptureStatus(
  val running: Boolean = false,
  /** Geofences currently registered with the platform, including the macro anchor. */
  val registeredCount: Int = 0,
  /** Why the last registration failed, or null when it succeeded. */
  val error: String? = null,
  val ridesRecorded: Int = 0,
  val lastRecordedRide: CompleteRideRecord? = null,
)

/**
 * Keeps the rolling station geofence window registered and feeds station entries to
 * [PassiveRideReconstructor]. Everything runs in [scope]; ride writes happen there, so pass a scope
 * on the dispatcher that owns the DAO.
 *
 * Station entries are read from [pendingEvents], which the platform receiver appends to, as well as
 * from [events]. Each entry is handled once. After handling, the store keeps only the entries of
 * the chain that is still open and of any chain whose ride could not be saved, so a restart replays
 * them and entries recorded while nothing was running are not lost. A chain idle for the
 * reconstructor's idle period is closed by a timer, and by [IdleCloseScheduler] when this process
 * is no longer running by then.
 */
class PassiveCaptureCoordinator(
  private val windowManager: DynamicGeofenceWindowManager,
  private val reconstructor: PassiveRideReconstructor,
  private val registrar: GeofenceRegistrar,
  private val events: Flow<GeofenceEvent>,
  private val scope: CoroutineScope,
  private val clock: Clock = Clock.System,
  private val pendingEvents: PendingGeofenceEventStore = InMemoryPendingGeofenceEventStore(),
  /** Called for every ride recorded, once it has been saved. */
  private val onRecorded: (CompleteRideRecord) -> Unit = {},
  /** Closes an idle chain after this process has died; see [scheduleIdleClose]. */
  private val idleCloseScheduler: IdleCloseScheduler = IdleCloseScheduler.None,
) {
  private val _status = MutableStateFlow(PassiveCaptureStatus())
  val status: StateFlow<PassiveCaptureStatus> = _status.asStateFlow()

  private var job: Job? = null
  private var idleTimer: Job? = null
  private var lastLat = 0.0
  private var lastLng = 0.0

  /** Entries already given to the reconstructor that are still in [pendingEvents]. */
  private val handled = mutableSetOf<GeofenceEvent>()

  /**
   * Replays pending entries, registers the window around the given point and starts handling
   * transitions. Calling it again while running retries the registration if the last attempt
   * failed, and otherwise does nothing.
   */
  fun start(initialLat: Double, initialLng: Double) {
    val running = job
    if (running != null && running.isActive) {
      if (_status.value.error != null) scope.launch { register(lastLat, lastLng) }
      return
    }
    _status.update { it.copy(running = true) }
    job = scope.launch {
      drainPending()
      closeIfIdle()
      launch(start = CoroutineStart.UNDISPATCHED) {
        events.collect { event ->
          // Leaving the macro anchor moves the window; any other transition is a station's.
          if (
            event is GeofenceEvent.Exited &&
              event.geofenceId == DynamicGeofenceWindowManager.MACRO_ANCHOR_ID
          ) {
            register(event.lat, event.lng)
          } else {
            handleStationEvent(event)
            drainPending()
          }
        }
      }
      register(initialLat, initialLng)
    }
  }

  /**
   * Handles the stored entries and closes an idle chain, without registering geofences or
   * collecting [events]; e.g. when the platform woke the app with nothing else running. Entries
   * kept for a ride that could not be saved are offered again, so the ride is recorded once storage
   * works.
   */
  fun catchUp() {
    // Offering the open chain's entries again is harmless: they are not newer than the chain.
    if (reconstructor.unsavedChainOriginTimeMs != null) handled.clear()
    drainPending()
    closeIfIdle()
  }

  /**
   * Registers the window again at the last point, e.g. after the platform dropped the geofences.
   */
  fun reregister() {
    val running = job ?: return
    if (running.isActive) scope.launch { register(lastLat, lastLng) }
  }

  /**
   * Stops handling transitions and removes the registered geofences. The open chain stays in
   * [pendingEvents].
   */
  fun stop() {
    job?.cancel()
    job = null
    idleTimer?.cancel()
    idleTimer = null
    handled.clear()
    _status.update { it.copy(running = false, registeredCount = 0, error = null) }
    scope.launch {
      try {
        registrar.clear()
      } catch (e: GeofenceRegistrationException) {
        DiagnosticsLog.warn(LOG_TAG, "Geofences could not be removed when capture stopped", e)
      }
    }
  }

  private suspend fun register(lat: Double, lng: Double) {
    lastLat = lat
    lastLng = lng
    val specs = windowManager.computeRollingWindow(lat, lng).activeGeofences
    try {
      registrar.replaceAll(specs)
      _status.update { it.copy(registeredCount = specs.size, error = null) }
    } catch (e: GeofenceRegistrationException) {
      _status.update {
        it.copy(registeredCount = 0, error = e.message ?: "Geofences could not be registered")
      }
    }
  }

  /**
   * Handles every stored entry not yet handled, oldest first, then trims the store to the open
   * chain.
   */
  private fun drainPending() {
    // At the same instant an exit comes first: it says where the ride began.
    pendingEvents
      .readAll()
      .sortedWith(compareBy({ it.epochMs }, { it is GeofenceEvent.Entered }))
      .forEach { handleStationEvent(it) }
    trimPending()
  }

  private fun handleStationEvent(event: GeofenceEvent) {
    val complexId = DynamicGeofenceWindowManager.complexIdForGeofence(event.geofenceId)
    if (complexId != null && handled.add(event)) {
      recorded(
        when (event) {
          is GeofenceEvent.Entered ->
            reconstructor.onStationGeofenceEntered(complexId, event.epochMs)
          is GeofenceEvent.Exited -> reconstructor.onStationGeofenceExited(complexId, event.epochMs)
        },
      )
      scheduleIdleClose()
    }
  }

  private fun closeIfIdle() {
    recorded(reconstructor.closeIfIdle(clock.now().toEpochMilliseconds()))
    trimPending()
    scheduleIdleClose()
  }

  /**
   * Arms the in-process timer and the platform alarm to close the open chain once idle, or cancels
   * the alarm when no chain is open. The timer dies with the process; the alarm does not, and calls
   * [catchUp] through the runtime.
   */
  private fun scheduleIdleClose() {
    idleTimer?.cancel()
    val lastMs = reconstructor.chainLastTimeMs
    if (lastMs == null) {
      idleCloseScheduler.cancel()
      return
    }
    val closeAtMs = lastMs + reconstructor.idleCloseMs
    idleCloseScheduler.schedule(closeAtMs)
    val waitMs = closeAtMs - clock.now().toEpochMilliseconds()
    idleTimer = scope.launch {
      if (waitMs > 0) delay(waitMs)
      closeIfIdle()
    }
  }

  /**
   * Keeps only the entries a restart needs to replay: those of the open chain, and those of any
   * chain whose ride could not be saved.
   */
  private fun trimPending() {
    val originMs =
      listOfNotNull(reconstructor.chainOriginTimeMs, reconstructor.unsavedChainOriginTimeMs)
        .minOrNull()
    val keep =
      if (originMs == null) emptyList()
      else pendingEvents.readAll().filter { it.epochMs >= originMs }
    pendingEvents.replaceAll(keep)
    handled.retainAll(keep.toSet())
  }

  private fun recorded(record: CompleteRideRecord?) {
    if (record == null) return
    _status.update { it.copy(ridesRecorded = it.ridesRecorded + 1, lastRecordedRide = record) }
    onRecorded(record)
  }
}

private const val LOG_TAG = "StraphangerCapture"
