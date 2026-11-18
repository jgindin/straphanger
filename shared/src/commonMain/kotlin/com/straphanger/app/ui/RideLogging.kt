package com.straphanger.app.ui

import com.straphanger.app.data.gtfs.ResolvedTripResult
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.TripLegRequest
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideIds
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.data.local.changeRideStations
import com.straphanger.app.ui.rides.stationName
import kotlinx.coroutines.flow.StateFlow

/** Logging, reviewing and deleting rides on this device. */
interface RideLogging : StraphangerScreenState {
  /**
   * Logs a manual ride from the log form (or the given stations) and closes the log sheet. Returns
   * null when the network has not loaded, the stations are the same, no route connects them, or the
   * ride could not be saved.
   *
   * @param timestampEpochMs when the ride started; null means now.
   */
  fun submitQuickRide(
    originStationId: String = uiState.value.logOriginStationId,
    transferStationId: String? = uiState.value.logTransferStationId,
    destinationStationId: String = uiState.value.logDestinationStationId,
    preferredRouteId: String? = uiState.value.logPreferredRouteId,
    forceExpress: Boolean? = uiState.value.logForceExpress,
    timestampEpochMs: Long? = null,
  ): CompleteRideRecord?

  /** Records a made-up passive ride that needs review (a developer tool). */
  fun simulateAmbiguousPassiveRide(): CompleteRideRecord?

  fun confirmAmbiguousRide(rideId: String, routeId: String, isExpress: Boolean)

  /** Clears the review flag of [rideId] without changing its legs. */
  fun keepRideAsIs(rideId: String)

  /**
   * Changes where [rideId] started and ended, finding the lines again. Returns false, leaving the
   * ride as it was, when the stations are the same, no route joins them, or the ride could not be
   * saved.
   */
  fun changeRideStations(
    rideId: String,
    originStationId: String,
    destinationStationId: String,
  ): Boolean

  /** Deletes [rideId]. */
  fun deleteRide(rideId: String)

  fun clearAllRides()

  /** Adds three sample rides so the map and statistics have something to show. */
  fun loadDemoRides()

  fun seedStarterNycRides()
}

/** The stations and route choices of a manual ride. */
private data class QuickRideForm(
  val originStationId: String,
  val transferStationId: String?,
  val destinationStationId: String,
  val preferredRouteId: String?,
  val forceExpress: Boolean?,
) {
  val changesTrains: Boolean
    get() =
      !transferStationId.isNullOrBlank() &&
        transferStationId != originStationId &&
        transferStationId != destinationStationId
}

private const val QUICK_RIDE_MINUTES = 18
private const val SIMULATED_RIDE_MINUTES = 10
private const val SIMULATED_RIDE_ACTUAL_MINUTES = 10.5
private const val MS_PER_MINUTE = 60_000L

/** 2026-11-11 09:00 in New York, a Wednesday. */
private const val SIMULATED_RIDE_START_EPOCH_MS = 1_794_405_600_000L

internal class RideLogController(private val session: StraphangerSession) : RideLogging {
  override val uiState: StateFlow<StraphangerUiState>
    get() = session.state

  private val dao
    get() = session.dao

  private val graphEngine: SubwayGraphEngine?
    get() = session.services?.graphEngine

  override fun submitQuickRide(
    originStationId: String,
    transferStationId: String?,
    destinationStationId: String,
    preferredRouteId: String?,
    forceExpress: Boolean?,
    timestampEpochMs: Long?,
  ): CompleteRideRecord? {
    val engine = graphEngine
    val form =
      QuickRideForm(
        originStationId,
        transferStationId,
        destinationStationId,
        preferredRouteId,
        forceExpress,
      )
    val record =
      if (engine == null || originStationId == destinationStationId) null
      else
        quickRideRecord(
          engine,
          form,
          timestampEpochMs ?: session.clock.now().toEpochMilliseconds(),
        )
    return record?.takeIf { saveQuickRide(it) }
  }

  private fun quickRideRecord(
    engine: SubwayGraphEngine,
    form: QuickRideForm,
    timestampEpochMs: Long,
  ): CompleteRideRecord? {
    val resolved = engine.resolveQuickRide(form, timestampEpochMs)
    return if (resolved.legs.isEmpty()) null
    else
      SubwayDao.buildCompleteRideRecord(
        rideId = RideIds.newId("manual"),
        startTimeEpochMs = timestampEpochMs,
        endTimeEpochMs = timestampEpochMs + QUICK_RIDE_MINUTES * MS_PER_MINUTE,
        source = RideSource.MANUAL,
        resolved = resolved,
        graphEngine = engine,
        forceNeedsReview = false,
      )
  }

  /** Saves [record] and closes the log sheet; false when the ride could not be saved. */
  private fun saveQuickRide(record: CompleteRideRecord): Boolean {
    dao.insertOrReplaceRide(record)
    val saved = dao.lastSaveSucceeded
    if (saved) {
      session.update {
        it.copy(
          isLogRideSheetOpen = false,
          statusMessage =
            "Logged ride (${record.ride.totalDistanceMiles} mi across " +
              "${countOf(record.legs.size, "leg")})",
        )
      }
    }
    return saved
  }

  override fun simulateAmbiguousPassiveRide(): CompleteRideRecord? {
    val engine = graphEngine ?: return null
    // A weekday morning, so express service runs and the ride is ambiguous whatever the time now.
    val ts = SIMULATED_RIDE_START_EPOCH_MS
    val resolved =
      engine.resolveOriginToDestinationAuto(
        originStationId = StationIds.UNION_SQ_LEX,
        destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
        preferredRouteId = null,
        timestampEpochMs = ts,
        actualDurationMinutes = SIMULATED_RIDE_ACTUAL_MINUTES,
      )
    val record =
      SubwayDao.buildCompleteRideRecord(
        rideId = RideIds.newId("passive_sim"),
        startTimeEpochMs = ts,
        endTimeEpochMs = ts + SIMULATED_RIDE_MINUTES * MS_PER_MINUTE,
        source = RideSource.PASSIVE_GPS,
        resolved = resolved,
        graphEngine = engine,
        forceNeedsReview = true,
      )
    dao.insertOrReplaceRide(record)
    session.setStatusIfSaved {
      val choices = record.ride.ambiguousCandidates.joinToString(" or ") { c -> c.label }
      "Simulated passive ride 14 St-Union Sq → 86 St" +
        if (choices.isNotEmpty()) ": confirm $choices on the Rides tab" else ""
    }
    return record
  }

  override fun confirmAmbiguousRide(rideId: String, routeId: String, isExpress: Boolean) {
    val engine = graphEngine ?: return
    dao.confirmAmbiguousRide(rideId, routeId, isExpress, engine)
    session.setStatusIfSaved {
      "Confirmed $routeId ${if (isExpress) "Express" else "Local"} for ride $rideId"
    }
  }

  override fun keepRideAsIs(rideId: String) {
    dao.markReviewed(rideId) ?: return
    session.setStatusIfSaved { "Kept the ride as recorded" }
  }

  override fun changeRideStations(
    rideId: String,
    originStationId: String,
    destinationStationId: String,
  ): Boolean {
    val engine = graphEngine
    val updated = engine?.let {
      dao.changeRideStations(rideId, originStationId, destinationStationId, it)
    }
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

  override fun deleteRide(rideId: String) {
    if (dao.getRideById(rideId) == null) return
    dao.deleteRide(rideId)
    session.setStatusIfSaved { "Deleted the ride" }
  }

  override fun clearAllRides() {
    dao.clearAll()
    session.setStatusIfSaved { "Cleared all local rides" }
  }

  override fun loadDemoRides() {
    if (graphEngine == null) return
    seedStarterNycRides()
    session.setStatusIfSaved { "Loaded 3 sample rides" }
  }

  override fun seedStarterNycRides() {
    val engine = graphEngine ?: return
    dao.insertOrReplaceRides(starterNycRides(engine, session.clock.now().toEpochMilliseconds()))
  }
}

private fun SubwayGraphEngine.resolveQuickRide(
  form: QuickRideForm,
  timestampEpochMs: Long,
): ResolvedTripResult =
  if (form.changesTrains) resolveViaTransfer(form, timestampEpochMs)
  else
    resolveOriginToDestinationAuto(
      originStationId = form.originStationId,
      destinationStationId = form.destinationStationId,
      preferredRouteId = form.preferredRouteId,
      forceExpress = form.forceExpress,
      timestampEpochMs = timestampEpochMs,
    )

/**
 * Resolves each side of the transfer on its own, then the whole trip from the legs found. The
 * form's route choices apply to the first leg after the transfer.
 */
private fun SubwayGraphEngine.resolveViaTransfer(
  form: QuickRideForm,
  timestampEpochMs: Long,
): ResolvedTripResult {
  val transferStationId = requireNotNull(form.transferStationId)
  val toTransfer =
    resolveOriginToDestinationAuto(
      originStationId = form.originStationId,
      destinationStationId = transferStationId,
      timestampEpochMs = timestampEpochMs,
    )
  val fromTransfer =
    resolveOriginToDestinationAuto(
      originStationId = transferStationId,
      destinationStationId = form.destinationStationId,
      preferredRouteId = form.preferredRouteId,
      forceExpress = form.forceExpress,
      timestampEpochMs = timestampEpochMs,
    )
  val firstLegAfterTransfer = toTransfer.legs.size
  val combinedRequests =
    (toTransfer.legs + fromTransfer.legs).mapIndexed { idx, l ->
      val chosen = idx == firstLegAfterTransfer
      TripLegRequest(
        fromStationId = l.fromStationId,
        toStationId = l.toStationId,
        preferredRouteId = if (chosen) (form.preferredRouteId ?: l.routeId) else l.routeId,
        forceExpress = if (chosen) (form.forceExpress ?: l.isExpress) else l.isExpress,
      )
    }
  return resolveTrip(combinedRequests, timestampEpochMs)
}
