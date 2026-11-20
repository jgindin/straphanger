package com.straphanger.app.ui

import com.straphanger.app.data.capture.NetworkServices
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.data.sync.BackupExportManager
import com.straphanger.app.domain.stats.SubwayStatsCalculator
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The state the view model's collaborators share: the scope every DAO write runs in, the shared
 * DAO, the screen state and, once the network has loaded, the [NetworkServices].
 */
internal class StraphangerSession(
  /** The process-wide DAO, from [com.straphanger.app.data.capture.RideCaptureRuntime]. */
  val dao: SubwayDao,
  val clock: Clock,
  dispatcher: CoroutineDispatcher,
) {
  /** The view model's own scope, on the runtime's dispatcher; cleared with the view model. */
  val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
