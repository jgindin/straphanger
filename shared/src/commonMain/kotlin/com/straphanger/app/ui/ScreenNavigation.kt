package com.straphanger.app.ui

import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import kotlinx.coroutines.flow.StateFlow

/** The screen state that the defaults of the view model's actions are read from. */
interface StraphangerScreenState {
  val uiState: StateFlow<StraphangerUiState>
}

/** Tab, map and statistics choices, and the ride log form. */
interface ScreenNavigation : StraphangerScreenState {
  fun selectTab(tab: AppTab)

  /** Shows the Rides tab, unfiltered, with [rideId] highlighted and scrolled into view. */
  fun showRide(rideId: String)

  fun setMapColorMode(mode: MapColorMode)

  /** Selects [routeId] on the map, or clears the filter when it is already selected. */
  fun setRouteFilter(routeId: String?)

  fun selectStationOnMap(stationId: String?)

  /** Lists only the rides on [routeId] (all rides when null) and shows the Rides tab. */
  fun showRidesOnRoute(routeId: String?)

  fun setStationGroupingMode(mode: StationGroupingMode)

  fun setStationVisitFilterMode(mode: StationVisitFilterMode)

  fun openLogRideSheet(
    originId: String? = null,
    destinationId: String? = null,
    transferStationId: String? = null,
  )

  fun closeLogRideSheet()

  fun updateLogForm(
    originId: String = uiState.value.logOriginStationId,
    transferId: String? = uiState.value.logTransferStationId,
    destinationId: String = uiState.value.logDestinationStationId,
    preferredRouteId: String? = uiState.value.logPreferredRouteId,
    forceExpress: Boolean? = uiState.value.logForceExpress,
  )
}

internal class ScreenNavigationController(private val session: StraphangerSession) :
  ScreenNavigation {
  override val uiState: StateFlow<StraphangerUiState>
    get() = session.state

  override fun selectTab(tab: AppTab) {
    session.update { it.copy(activeTab = tab, highlightedRideId = null) }
  }

  override fun showRide(rideId: String) {
    session.update {
      it.copy(activeTab = AppTab.RIDES, highlightedRideId = rideId, ridesRouteFilter = null)
    }
  }

  override fun setMapColorMode(mode: MapColorMode) {
    session.update { it.copy(mapColorMode = mode) }
  }

  override fun setRouteFilter(routeId: String?) {
    session.update {
      it.copy(selectedRouteFilter = if (it.selectedRouteFilter == routeId) null else routeId)
    }
  }

  override fun selectStationOnMap(stationId: String?) {
    session.update { it.copy(selectedStationId = stationId) }
  }

  override fun showRidesOnRoute(routeId: String?) {
    session.update {
      it.copy(
        ridesRouteFilter = routeId,
        activeTab = if (routeId == null) it.activeTab else AppTab.RIDES,
      )
    }
  }

  override fun setStationGroupingMode(mode: StationGroupingMode) {
    session.update { it.copy(stationGroupingMode = mode) }
  }

  override fun setStationVisitFilterMode(mode: StationVisitFilterMode) {
    session.update { it.copy(stationVisitFilterMode = mode) }
  }

  override fun openLogRideSheet(
    originId: String?,
    destinationId: String?,
    transferStationId: String?,
  ) {
    session.update {
      it.copy(
        isLogRideSheetOpen = true,
        logOriginStationId = originId ?: it.logOriginStationId,
        logDestinationStationId = destinationId ?: it.logDestinationStationId,
        logTransferStationId = transferStationId,
      )
    }
  }

  override fun closeLogRideSheet() {
    session.update { it.copy(isLogRideSheetOpen = false) }
  }

  override fun updateLogForm(
    originId: String,
    transferId: String?,
    destinationId: String,
    preferredRouteId: String?,
    forceExpress: Boolean?,
  ) {
    session.update {
      it.copy(
        logOriginStationId = originId,
        logTransferStationId = transferId,
        logDestinationStationId = destinationId,
        logPreferredRouteId = preferredRouteId,
        logForceExpress = forceExpress,
      )
    }
  }
}
