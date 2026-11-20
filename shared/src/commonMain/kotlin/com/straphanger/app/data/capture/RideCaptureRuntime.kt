package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.data.local.SubwayDao
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import org.jetbrains.compose.resources.MissingResourceException

/**
 * The process-wide owner of the rides file and of everything that records rides without the user:
 * the [dao], the loaded network, passive capture and Google Maps navigation sessions. The view
 * model and the platform's background receivers share one instance, so there is one writer of the
 * rides file. Every DAO write runs on [dispatcher], in [scope], which nothing cancels.
 *
 * Each ride that passive capture or a navigation session saves is posted to [notifier] and emitted
 * on [recordedRides]. Rides the user enters are not.
 */
class RideCaptureRuntime(
  backend: RidePersistenceBackend,
  val clock: Clock = Clock.System,
  val dispatcher: CoroutineDispatcher,
  networkLoader: suspend () -> SubwayNetworkData = { NycSubwayNetworkCatalog.loadBundled() },
  /** Registers station geofences; null on platforms without passive capture. */
  private val registrar: GeofenceRegistrar? = null,
  private val geofenceEvents: Flow<GeofenceEvent> = GeofenceEventBus.events,
  /** Station entries the platform receiver recorded, including those received while nothing ran. */
  private val pendingEvents: PendingGeofenceEventStore = InMemoryPendingGeofenceEventStore(),
  /** Google Maps navigation sessions the notification listener recorded. */
  notificationSessions: NotificationSessionStore = InMemoryNotificationSessionStore(),
  private val notificationSessionsEnded: Flow<Int> = NotificationSessionSignal.endedCount,
  private val notifier: RideNotifier = RideNotifier.None,
  /** Wakes the process to close an idle ride chain; see [PassiveCaptureCoordinator]. */
  private val idleCloseScheduler: IdleCloseScheduler = IdleCloseScheduler.None,
) {
  /** Never cancelled: a view model keeps its own scope. */
  val scope = CoroutineScope(SupervisorJob() + dispatcher)
  val dao = SubwayDao(backend, clock)

  private val _recordedRides = MutableSharedFlow<CompleteRideRecord>(extraBufferCapacity = 16)

  /** Every ride passive capture or a navigation session saved, as it is saved. */
  val recordedRides: SharedFlow<CompleteRideRecord> = _recordedRides.asSharedFlow()

  /** The loaded network's services, or null while loading or after a failed load. */
  var services: NetworkServices? = null
    private set

  /** Null until the network loads, or when [registrar] is null. */
  var coordinator: PassiveCaptureCoordinator? = null
    private set

  private val navigation =
    NavigationSessionRecorder(dao, { services }, notificationSessions, ::onSaved)

  private val networkLoad =
    scope.async(start = CoroutineStart.LAZY) {
      loadBundledData(networkLoader).onSuccess { onNetworkLoaded(it) }
    }

  // Collecting replays the current count, so sessions that ended while nothing ran are drained at
  // start.
  private val navigationCollector =
    scope.launch(start = CoroutineStart.LAZY) {
      if (network().isSuccess) notificationSessionsEnded.collect { navigation.drain() }
    }

  /** Loads the network once; later calls return the same result. */
  suspend fun network(): Result<SubwayNetworkData> = networkLoad.await()

  /** Starts the network load and the navigation-session collector; idempotent. */
  fun start() {
    networkLoad.start()
    navigationCollector.start()
  }

  /**
   * Turns what the platform recorded while nothing ran into rides: stored station entries, an idle
   * chain, and ended navigation sessions. Safe to call any number of times.
   *
   * @param passiveCapture false skips the station entries and the idle chain, e.g. when the user
   *   turned passive capture off or revoked location access; navigation sessions need neither.
   */
  suspend fun catchUp(passiveCapture: Boolean = true) {
    start()
    if (network().isFailure) return
    withContext(dispatcher) {
      if (passiveCapture) coordinator?.catchUp()
      navigation.drain()
    }
  }

  private fun onNetworkLoaded(network: SubwayNetworkData) {
    val loaded = NetworkServices(SubwayGraphEngine(network), clock)
    services = loaded
    coordinator = registrar?.let {
      PassiveCaptureCoordinator(
        windowManager = loaded.geofenceManager,
        reconstructor =
          PassiveRideReconstructor(
            loaded.graphEngine,
            dao,
            stationRadiusMeters = loaded.geofenceManager.stationRadiusMeters,
          ),
        registrar = it,
        events = geofenceEvents,
        scope = scope,
        clock = clock,
        pendingEvents = pendingEvents,
        onRecorded = ::onSaved,
        idleCloseScheduler = idleCloseScheduler,
      )
    }
  }

  /** Called only for a ride that was saved; both recorders skip failed saves and duplicates. */
  private fun onSaved(record: CompleteRideRecord) {
    val loaded = services ?: return
    notifier.rideRecorded(record.ride.rideId, rideNotificationText(record, loaded::stationName))
    _recordedRides.tryEmit(record)
  }
}

/**
 * Loads bundled data (the network or the basemap), returning the failures loading it can raise: a
 * missing bundled resource, an I/O error, bad JSON ([kotlinx.serialization.SerializationException]
 * is an [IllegalArgumentException]), or an [IllegalStateException]. Cancellation is rethrown.
 */
internal suspend fun <T> loadBundledData(loader: suspend () -> T): Result<T> =
  try {
    Result.success(loader())
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
