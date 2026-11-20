package com.straphanger.app.ui

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RouteChoice
import com.straphanger.app.data.local.changeRideStations
import com.straphanger.app.data.local.combineRides
import com.straphanger.app.ui.rides.stationName
import kotlinx.coroutines.flow.StateFlow

/** Correcting a recorded ride: its stations, its line, or a ride the app split in two. */
interface RideEditing : StraphangerScreenState {
  /**
   * Changes where [rideId] started and ended, finding the lines again. With a [choice] of express
   * or local the ride is stored on that line; without one the app decides. Returns false, leaving
   * the ride as it was, when the stations are the same, no route joins them, or the ride could not
   * be saved.
   */
  fun changeRideStations(
    rideId: String,
    originStationId: String,
    destinationStationId: String,
    choice: RouteChoice? = null,
  ): Boolean

  /**
   * Makes [firstId] and the ride after it, [secondId], one ride from the first's start to the
   * second's end. Returns false, changing nothing, when either ride is missing, they are the same
   * ride, no route joins the stations, or the ride could not be saved.
   */
  fun combineRides(firstId: String, secondId: String): Boolean
}

internal class RideEditController(private val session: StraphangerSession) : RideEditing {
  override val uiState: StateFlow<StraphangerUiState>
    get() = session.state

  private val dao
    get() = session.dao

  private val graphEngine: SubwayGraphEngine?
    get() = session.services?.graphEngine

  override fun changeRideStations(
    rideId: String,
    originStationId: String,
    destinationStationId: String,
    choice: RouteChoice?,
  ): Boolean = savedWithStatus { engine ->
    dao.changeRideStations(rideId, originStationId, destinationStationId, engine, choice)
  }

  override fun combineRides(firstId: String, secondId: String): Boolean =
    savedWithStatus { engine ->
      dao.combineRides(firstId, secondId, engine)
    }

  /** Runs [change]; when it saved a ride, says where the ride now runs. */
  private fun savedWithStatus(change: (SubwayGraphEngine) -> CompleteRideRecord?): Boolean {
    val engine = graphEngine
    val updated = engine?.let(change)
    val saved = updated != null && dao.lastSaveSucceeded
    if (saved && engine != null && updated != null) {
      session.update {
        it.copy(
          statusMessage =
            "Ride now runs ${engine.stationName(updated.ride.originStationId)} → " +
              engine.stationName(updated.ride.destinationStationId),
        )
      }
    }
    return saved
  }
}
