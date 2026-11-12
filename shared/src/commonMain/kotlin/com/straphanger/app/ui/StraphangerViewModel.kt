package com.straphanger.app.ui

import androidx.lifecycle.ViewModel
import com.straphanger.app.data.capture.GeofenceEvent
import com.straphanger.app.data.capture.GeofenceEventBus
import com.straphanger.app.data.capture.GeofenceRegistrar
import com.straphanger.app.data.capture.InMemoryNotificationSessionStore
import com.straphanger.app.data.capture.InMemoryPendingGeofenceEventStore
import com.straphanger.app.data.capture.NotificationSessionSignal
import com.straphanger.app.data.capture.NotificationSessionStore
import com.straphanger.app.data.capture.PendingGeofenceEventStore
import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.data.sync.BackupExportManager
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import org.jetbrains.compose.resources.MissingResourceException

/**
 * The app's screen state and actions. The actions live in collaborators that share one
 * [StraphangerSession]; the view model delegates to them, so its public API is theirs. Every DAO
 * write runs on the view model dispatcher; only Timeline parsing runs on the compute dispatcher.
 */
class StraphangerViewModel private constructor(private val parts: ViewModelParts) :
  ViewModel(parts.session.scope),
  ScreenNavigation by parts.navigation,
  RideLogging by parts.rideLog,
  TimelineFileImport by parts.timeline,
  BackupActions by parts.backup,
  CaptureAccessControl by parts.capture {

  constructor(
    backend: RidePersistenceBackend = InMemoryPersistenceBackend(),
    clock: Clock = Clock.System,
    seedInitialSampleRides: Boolean = false,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    networkLoader: suspend () -> SubwayNetworkData = { NycSubwayNetworkCatalog.loadBundled() },
    /** Registers station geofences; null on platforms without passive capture. */
    geofenceRegistrar: GeofenceRegistrar? = null,
    geofenceEvents: Flow<GeofenceEvent> = GeofenceEventBus.events,
    /**
     * Station entries the platform receiver recorded, including those received while no view model
     * ran.
     */
    pendingGeofenceEvents: PendingGeofenceEventStore = InMemoryPendingGeofenceEventStore(),
    /**
     * Google Maps navigation sessions the notification listener recorded; ended ones become rides.
     */
    notificationSessions: NotificationSessionStore = InMemoryNotificationSessionStore(),
    notificationSessionsEnded: Flow<Int> = NotificationSessionSignal.endedCount,
  ) : this(
    ViewModelParts(
      StraphangerSession(backend, clock, dispatcher),
      computeDispatcher,
      PassiveCaptureInputs(geofenceRegistrar, geofenceEvents, pendingGeofenceEvents),
      notificationSessions,
      notificationSessionsEnded,
    )
  ) {
    parts.start(seedInitialSampleRides, networkLoader)
  }

  override val uiState: StateFlow<StraphangerUiState> = parts.session.state.asStateFlow()

  /** The loaded network's engine, or null while the network is loading. */
  val graphEngine: SubwayGraphEngine?
    get() = parts.session.services?.graphEngine

  val dao: SubwayDao
    get() = parts.session.dao

  val backupManager: BackupExportManager
    get() = parts.session.backupManager
}

internal class PassiveCaptureInputs(
  val registrar: GeofenceRegistrar?,
  val events: Flow<GeofenceEvent>,
  val pendingEvents: PendingGeofenceEventStore,
)

/** The view model's collaborators, and the start-up that loads the network and wires them up. */
internal class ViewModelParts(
  val session: StraphangerSession,
  computeDispatcher: CoroutineDispatcher,
  capture: PassiveCaptureInputs,
  notificationSessions: NotificationSessionStore,
  notificationSessionsEnded: Flow<Int>,
) {
  val navigation = ScreenNavigationController(session)
  val rideLog = RideLogController(session)
  val timeline = TimelineImportController(session, computeDispatcher)
  val backup = BackupController(session)
  val capture =
    PassiveCaptureController(session, capture.registrar, capture.events, capture.pendingEvents)
  private val recorder =
    CapturedRideRecorder(session, notificationSessions, notificationSessionsEnded)

  fun start(seedInitialSampleRides: Boolean, networkLoader: suspend () -> SubwayNetworkData) {
    session.scope.launch {
      session.dao.loadError.collect { error -> session.update { it.copy(loadError = error) } }
    }
    session.scope.launch {
      loadNetwork(networkLoader)
        .fold(
          onSuccess = { network -> onNetworkLoaded(network, seedInitialSampleRides) },
          onFailure = { e -> reportNetworkLoadFailure(e) },
        )
    }
  }

  private fun onNetworkLoaded(network: SubwayNetworkData, seedInitialSampleRides: Boolean) {
    val services = NetworkServices(SubwayGraphEngine(network), session.clock)
    session.services = services
    session.update { it.copy(isNetworkLoaded = true) }
    if (seedInitialSampleRides && session.dao.getAllRides().isEmpty()) {
      rideLog.seedStarterNycRides()
    }
    session.scope.launch {
      session.dao.ridesFlow.collect { latestRides ->
        val latestStats = services.statsCalculator.computeStats(latestRides)
        session.update { it.copy(rides = latestRides, stats = latestStats) }
      }
    }
    recorder.start(services)
    capture.start(services)
  }

  private fun reportNetworkLoadFailure(e: Throwable) {
    DiagnosticsLog.warn(LOG_TAG, "The subway network could not be loaded", e)
    session.update {
      it.copy(networkLoadError = "The subway network could not be loaded: ${e.reason}")
    }
  }

  private companion object {
    const val LOG_TAG = "StraphangerNetwork"
  }
}

/**
 * Loads the network, returning the failures loading it can raise: a missing bundled resource, an
 * I/O error, bad JSON ([kotlinx.serialization.SerializationException] is an
 * [IllegalArgumentException]), or an [IllegalStateException]. Cancellation is rethrown.
 */
private suspend fun loadNetwork(
  networkLoader: suspend () -> SubwayNetworkData
): Result<SubwayNetworkData> =
  try {
    Result.success(networkLoader())
  } catch (e: MissingResourceException) {
    Result.failure(e)
  } catch (e: IOException) {
    Result.failure(e)
  } catch (e: IllegalArgumentException) {
    Result.failure(e)
  } catch (e: IllegalStateException) {
    if (e is CancellationException) throw e
    Result.failure(e)
  }
