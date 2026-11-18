package com.straphanger.app.ui

import com.straphanger.app.data.capture.DynamicGeofenceWindowManager
import com.straphanger.app.data.capture.GoogleMapsShareParser
import com.straphanger.app.data.capture.TimelineJsonImporter
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.data.sync.BackupExportManager
import com.straphanger.app.domain.stats.SubwayStatsCalculator
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Services that need the subway network; created once it has loaded. */
internal class NetworkServices(val graphEngine: SubwayGraphEngine, clock: Clock) {
  val statsCalculator = SubwayStatsCalculator(graphEngine)
  val timelineImporter = TimelineJsonImporter(graphEngine)
  val shareParser = GoogleMapsShareParser(graphEngine, clock = clock)
  val geofenceManager = DynamicGeofenceWindowManager(graphEngine)

  fun stationName(stationId: String): String = graphEngine.getStation(stationId)?.name ?: stationId
}

/**
 * The state the view model's collaborators share: the scope every DAO write runs in, the DAO, the
 * screen state and, once the network has loaded, the [NetworkServices].
 */
internal class StraphangerSession(
  backend: RidePersistenceBackend,
  val clock: Clock,
  dispatcher: CoroutineDispatcher,
) {
  val scope = CoroutineScope(SupervisorJob() + dispatcher)
  val dao = SubwayDao(backend, clock)
  val backupManager = BackupExportManager(dao, clock)

  val state =
    MutableStateFlow(
      StraphangerUiState(
        rides = dao.getAllRides(),
        stats =
          SubwayStatsCalculator(SubwayGraphEngine(SubwayNetworkData.EMPTY))
            .computeStats(emptyList()),
        loadError = dao.loadError.value,
      ),
    )

  /** Null while the network is loading. */
  var services: NetworkServices? = null

  fun update(transform: (StraphangerUiState) -> StraphangerUiState) {
    state.update(transform)
  }

  /** Shows [message] in the status banner and returns it. */
  fun setStatus(message: String): String {
    state.update { it.copy(statusMessage = message) }
    return message
  }

  /** Shows [message] only when the last DAO write was saved; a failed save reports itself. */
  fun setStatusIfSaved(message: () -> String) {
    if (dao.lastSaveSucceeded) setStatus(message())
  }
}
