package com.straphanger.app.ui

import androidx.lifecycle.ViewModel
import com.straphanger.app.data.basemap.NycBasemap
import com.straphanger.app.data.basemap.NycBasemapCatalog
import com.straphanger.app.data.capture.GeofenceEvent
import com.straphanger.app.data.capture.GeofenceEventBus
import com.straphanger.app.data.capture.GeofenceRegistrar
import com.straphanger.app.data.capture.InMemoryNotificationSessionStore
import com.straphanger.app.data.capture.InMemoryPendingGeofenceEventStore
import com.straphanger.app.data.capture.NotificationSessionSignal
import com.straphanger.app.data.capture.NotificationSessionStore
import com.straphanger.app.data.capture.PendingGeofenceEventStore
import com.straphanger.app.data.capture.RideCaptureRuntime
import com.straphanger.app.data.capture.loadBundledData
import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.data.sync.BackupExportManager
import com.straphanger.app.diagnostics.DiagnosticsLog
import com.straphanger.app.ui.map.projectBasemap
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's screen state and actions. The actions live in collaborators that share one
 * [StraphangerSession]; the view model delegates to them, so its public API is theirs. The DAO, the
 * network and capture belong to the process-wide [RideCaptureRuntime]. Every DAO write runs on the
 * runtime's dispatcher; only Timeline parsing runs on the compute dispatcher.
 */
class StraphangerViewModel private constructor(private val parts: ViewModelParts) :
  ViewModel(parts.session.scope),
  ScreenNavigation by parts.navigation,
  RideLogging by parts.rideLog,
  RideEditing by parts.rideEdit,
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
    /** The map drawn under the lines; the app works without it if it fails to load. */
    basemapLoader: suspend () -> NycBasemap = { NycBasemapCatalog.loadBundled() },
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
    /**
     * The process-wide capture runtime, which owns the DAO. When null, one is built from the
     * parameters above and cancelled with this view model; when given, those parameters are ignored
     * and the runtime is never cancelled here.
     */
    runtime: RideCaptureRuntime? = null,
  ) : this(
    ViewModelParts(
      runtime
        ?: RideCaptureRuntime(
          backend = backend,
          clock = clock,
          dispatcher = dispatcher,
          networkLoader = networkLoader,
          registrar = geofenceRegistrar,
          geofenceEvents = geofenceEvents,
          pendingEvents = pendingGeofenceEvents,
          notificationSessions = notificationSessions,
          notificationSessionsEnded = notificationSessionsEnded,
        ),
      computeDispatcher,
    ),
  ) {
    // A runtime built here is this view model's alone, so it ends with it, as capture did before
    // the runtime existed. A runtime passed in is shared and outlives the view model.
    if (runtime == null) addCloseable { parts.runtime.scope.cancel() }
    parts.start(seedInitialSampleRides, basemapLoader)
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

/** The view model's collaborators, and the start-up that loads the network and wires them up. */
internal class ViewModelParts(
  val runtime: RideCaptureRuntime,
  private val computeDispatcher: CoroutineDispatcher,
) {
  val session = StraphangerSession(runtime.dao, runtime.clock, runtime.dispatcher)
  val navigation = ScreenNavigationController(session)
  val rideLog = RideLogController(session)
  val rideEdit = RideEditController(session)
  val timeline = TimelineImportController(session, computeDispatcher)
  val backup = BackupController(session)
  val capture = PassiveCaptureController(session)
  private val recorder = CapturedRideRecorder(session)

  fun start(seedInitialSampleRides: Boolean, basemapLoader: suspend () -> NycBasemap) {
    session.scope.launch {
      session.dao.loadError.collect { error -> session.update { it.copy(loadError = error) } }
    }
    // Subscribed before the runtime starts, so rides recorded at start-up are shown too.
    session.scope.launch { runtime.recordedRides.collect { showRecordedRide(it) } }
    runtime.start()
    session.scope.launch {
      runtime
        .network()
        .fold(
          onSuccess = { onNetworkLoaded(seedInitialSampleRides) },
          onFailure = { e -> reportNetworkLoadFailure(e) },
        )
    }
    // Parsed and projected on the compute dispatcher, beside the network. Without it the map is
    // drawn on the plain background.
    session.scope.launch {
      loadBundledData { withContext(computeDispatcher) { projectBasemap(basemapLoader()) } }
        .fold(
          onSuccess = { basemap -> session.update { it.copy(basemap = basemap) } },
          onFailure = { e ->
            DiagnosticsLog.warn(BASEMAP_LOG_TAG, "The basemap could not be loaded", e)
          },
        )
    }
  }

  private fun onNetworkLoaded(seedInitialSampleRides: Boolean) {
    val services = checkNotNull(runtime.services) { "The network loaded without its services" }
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
    capture.start(runtime.coordinator)
  }

  /** Shows the status for a ride the runtime recorded from passive capture or Google Maps. */
  private fun showRecordedRide(record: CompleteRideRecord) {
    val services = runtime.services ?: return
    val ride = record.ride
    session.setStatus(
      if (ride.source == RideSource.PASSIVE_GPS) {
        recordedPassiveRideMessage(services, ride)
      } else {
        "Recorded ride from Google Maps navigation: " +
          "${services.stationName(ride.originStationId)} → " +
          services.stationName(ride.destinationStationId)
      },
    )
  }

  private fun reportNetworkLoadFailure(e: Throwable) {
    DiagnosticsLog.warn(LOG_TAG, "The subway network could not be loaded", e)
    session.update {
      it.copy(networkLoadError = "The subway network could not be loaded: ${e.reason}")
    }
  }

  private companion object {
    const val LOG_TAG = "StraphangerNetwork"
    const val BASEMAP_LOG_TAG = "StraphangerBasemap"
  }
}
