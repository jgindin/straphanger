package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.ResolvedTripResult
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.TripLegRequest
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where [SubwayDao] keeps its rides. Each call reports a failure by throwing a
 * [kotlinx.io.IOException] (the file could not be read or written) or an [IllegalStateException]
 * (the store cannot be used); the DAO handles those two and lets anything else propagate.
 */
interface RidePersistenceBackend {
  fun loadSnapshotJson(): String?

  fun saveSnapshotJson(json: String)

  /** The previous good snapshot kept by the last save, or null when there is none. */
  fun loadBackupSnapshotJson(): String? = null

  /**
   * Moves an unreadable snapshot aside, named with [suffix], so a later save cannot overwrite it.
   */
  fun quarantineCorruptSnapshot(suffix: String) {}
}

class InMemoryPersistenceBackend(initialJson: String? = null) : RidePersistenceBackend {
  private var stored: String? = initialJson

  override fun loadSnapshotJson(): String? = stored

  override fun saveSnapshotJson(json: String) {
    stored = json
  }
}

class SubwayDao(
  private val backend: RidePersistenceBackend = InMemoryPersistenceBackend(),
  clock: Clock = Clock.System,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val initialLoad = RideSnapshotLoader(backend, json, clock).load()

  private val _ridesFlow = MutableStateFlow(initialLoad.rides)
  val ridesFlow: StateFlow<List<CompleteRideRecord>> = _ridesFlow.asStateFlow()

  private val _loadError = MutableStateFlow(initialLoad.error)

  /** Set when the stored rides could not be read at startup; the app then starts with no rides. */
  val loadError: StateFlow<String?> = _loadError.asStateFlow()

  /** False when the most recent change could not be saved; the rides are then left as they were. */
  var lastSaveSucceeded: Boolean = true
    private set

  // True when an unreadable file could not be set aside; saving would overwrite it.
  private val savesBlocked = initialLoad.savesBlocked

  fun getAllRides(): List<CompleteRideRecord> = _ridesFlow.value

  fun getRideById(rideId: String): CompleteRideRecord? =
    _ridesFlow.value.firstOrNull { it.ride.rideId == rideId }

  fun insertOrReplaceRide(record: CompleteRideRecord) {
    val updated = _ridesFlow.value.filterNot { it.ride.rideId == record.ride.rideId } + record
    reportingSaveFailure { persistThenPublish(updated) }
  }

  fun insertOrReplaceRides(records: List<CompleteRideRecord>) {
    reportingSaveFailure { replaceRidesOrThrow(records) }
  }

  /**
   * Like [insertOrReplaceRides], but a failure to save is thrown and the rides are left unchanged.
   * The failure is the backend's [kotlinx.io.IOException] or [IllegalStateException], or an
   * [IllegalStateException] when saves are blocked.
   */
  fun replaceRidesOrThrow(records: List<CompleteRideRecord>) {
    if (records.isEmpty()) return
    val incomingIds = records.map { it.ride.rideId }.toSet()
    val updated = _ridesFlow.value.filterNot { it.ride.rideId in incomingIds } + records
    persistThenPublish(updated)
  }

  fun deleteRide(rideId: String) {
    val updated = _ridesFlow.value.filterNot { it.ride.rideId == rideId }
    reportingSaveFailure { persistThenPublish(updated) }
  }

  fun clearAll() {
    reportingSaveFailure { persistThenPublish(emptyList()) }
  }

  /**
   * Resolves an ambiguous Express vs. Local passive ride when the user taps Confirm in the "Needs
   * Review" banner or notification.
   */
  fun confirmAmbiguousRide(
    rideId: String,
    confirmedRouteId: String,
    confirmedIsExpress: Boolean,
    graphEngine: SubwayGraphEngine,
  ): CompleteRideRecord? {
    val existing = getRideById(rideId) ?: return null
    val legRequests = confirmedLegRequests(existing, confirmedRouteId, confirmedIsExpress)
    val resolved =
      graphEngine.resolveTrip(
        legs = legRequests,
        timestampEpochMs = existing.ride.startTimeEpochMs,
        actualDurationMinutes = existing.ride.durationMinutes,
      )
    val updatedRecord =
      buildCompleteRideRecord(
        rideId = existing.ride.rideId,
        startTimeEpochMs = existing.ride.startTimeEpochMs,
        endTimeEpochMs = existing.ride.endTimeEpochMs,
        source = existing.ride.source,
        resolved = resolved.copy(isAmbiguous = false, ambiguousCandidates = emptyList()),
        graphEngine = graphEngine,
        notes = existing.ride.notes,
      )
    insertOrReplaceRide(updatedRecord)
    return updatedRecord
  }

  /**
   * Clears the review flag of [rideId] and keeps its legs. Returns the updated ride, or null if
   * there is none.
   */
  fun markReviewed(rideId: String): CompleteRideRecord? {
    val existing = getRideById(rideId) ?: return null
    val updated =
      existing.copy(
        ride =
          existing.ride.copy(
            needsReview = false,
            ambiguousCandidates = emptyList(),
            overlapsRideIds = emptyList(),
          ),
      )
    insertOrReplaceRide(updated)
    return updated
  }

  // Records a failure to save in [loadError]; the rides stay as they were.
  private inline fun reportingSaveFailure(save: () -> Unit) {
    val failure = catchingBackendFailure(save).exceptionOrNull() ?: return
    lastSaveSucceeded = false
    _loadError.value =
      "$SAVE_ERROR_PREFIX ${failure.message?.removeSuffix(".") ?: "unknown error"}. " +
        "The change was not applied."
  }

  // Saves first and publishes only on success, so memory never holds rides that were not saved.
  // While saves are blocked nothing can be saved, so nothing is published either.
  private fun persistThenPublish(records: List<CompleteRideRecord>) {
    lastSaveSucceeded = false
    check(!savesBlocked) { SAVES_BLOCKED_MESSAGE }
    val sorted = records.sortedByDescending { it.ride.startTimeEpochMs }
    backend.saveSnapshotJson(json.encodeToString(sorted))
    lastSaveSucceeded = true
    if (_loadError.value?.startsWith(SAVE_ERROR_PREFIX) == true) _loadError.value = null
    _ridesFlow.value = sorted
  }

  companion object {
    private const val SAVE_ERROR_PREFIX = "Rides could not be saved:"
    private const val SAVES_BLOCKED_MESSAGE =
      "Saves are blocked because the existing rides file could not be read or moved aside."

    fun buildCompleteRideRecord(
      rideId: String,
      startTimeEpochMs: Long,
      endTimeEpochMs: Long,
      source: RideSource,
      resolved: ResolvedTripResult,
      graphEngine: SubwayGraphEngine,
      notes: String = "",
      forceNeedsReview: Boolean? = null,
    ): CompleteRideRecord {
      val durationMin = rideDurationMinutes(startTimeEpochMs, endTimeEpochMs, resolved)
      val rideEntity =
        RideEntity(
          rideId = rideId,
          startTimeEpochMs = startTimeEpochMs,
          endTimeEpochMs =
            if (endTimeEpochMs > startTimeEpochMs) endTimeEpochMs
            else startTimeEpochMs + (durationMin * 60000).toLong(),
          originStationId = resolved.legs.firstOrNull()?.fromStationId.orEmpty(),
          destinationStationId = resolved.legs.lastOrNull()?.toStationId.orEmpty(),
          totalDistanceMiles = resolved.totalDistanceMiles,
          durationMinutes = durationMin,
          source = source,
          needsReview = forceNeedsReview ?: resolved.isAmbiguous,
          ambiguousCandidates = resolved.ambiguousCandidates,
          notes = notes,
        )
      return CompleteRideRecord(
        ride = rideEntity,
        legs = legEntities(rideId, resolved),
        segments = segmentEntities(rideId, resolved, graphEngine),
        stationStops = stopEntities(rideId, resolved),
      )
    }
  }
}

private const val MS_PER_MINUTE = 60000.0
private const val ESTIMATED_SPEED_MPH = 18.0
private const val MINUTES_PER_HOUR = 60.0

// The clock time when the ride has one, otherwise an estimate at 18 mph; rounded to 0.1 minute.
private fun rideDurationMinutes(
  startTimeEpochMs: Long,
  endTimeEpochMs: Long,
  resolved: ResolvedTripResult,
): Double {
  val minutes =
    if (endTimeEpochMs > startTimeEpochMs) {
      (endTimeEpochMs - startTimeEpochMs) / MS_PER_MINUTE
    } else {
      resolved.totalDistanceMiles / ESTIMATED_SPEED_MPH * MINUTES_PER_HOUR
    }
  return (minutes * 10.0).roundToInt() / 10.0
}

private fun legEntities(rideId: String, resolved: ResolvedTripResult): List<RideLegEntity> =
  resolved.legs.map { leg ->
    RideLegEntity(
      legId = "${rideId}_leg_${leg.legIndex}",
      rideId = rideId,
      legIndex = leg.legIndex,
      routeId = leg.routeId,
      isExpress = leg.isExpress,
      servicePeriod = leg.servicePeriod,
      fromStationId = leg.fromStationId,
      toStationId = leg.toStationId,
      distanceMiles = leg.distanceMiles,
    )
  }

private fun segmentEntities(
  rideId: String,
  resolved: ResolvedTripResult,
  graphEngine: SubwayGraphEngine,
): List<RideSegmentEntity> =
  resolved.legs.flatMap { leg ->
    leg.segmentIds.mapNotNull { segId ->
      graphEngine.getSegment(segId)?.let { seg ->
        RideSegmentEntity(
          rideId = rideId,
          legIndex = leg.legIndex,
          segmentId = segId,
          routeId = leg.routeId,
          distanceMiles = seg.distanceMiles,
        )
      }
    }
  }

private fun stopEntities(
  rideId: String,
  resolved: ResolvedTripResult,
): List<RideStationStopEntity> =
  resolved.stationStops.map { stop ->
    RideStationStopEntity(
      rideId = rideId,
      stationId = stop.stationId,
      complexId = stop.complexId,
      stopRole = stop.role,
      legIndex = stop.legIndex,
    )
  }

// The first leg takes the confirmed route; the others keep theirs.
private fun confirmedLegRequests(
  existing: CompleteRideRecord,
  confirmedRouteId: String,
  confirmedIsExpress: Boolean,
): List<TripLegRequest> =
  existing.legs.mapIndexed { idx, leg ->
    TripLegRequest(
      fromStationId = leg.fromStationId,
      toStationId = leg.toStationId,
      preferredRouteId = if (idx == 0) confirmedRouteId else leg.routeId,
      forceExpress = if (idx == 0) confirmedIsExpress else leg.isExpress,
    )
  }
