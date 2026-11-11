package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.ResolvedTripResult
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.TripLegRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.time.Clock

interface RidePersistenceBackend {
    fun loadSnapshotJson(): String?
    fun saveSnapshotJson(json: String)

    /** Moves an unreadable snapshot aside, named with [suffix], so a later save cannot overwrite it. */
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
    private val clock: Clock = Clock.System
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _ridesFlow = MutableStateFlow<List<CompleteRideRecord>>(emptyList())
    val ridesFlow: StateFlow<List<CompleteRideRecord>> = _ridesFlow.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)

    /** Set when the stored rides could not be read at startup; the app then starts with no rides. */
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** False when the most recent change could not be saved; the rides are then left as they were. */
    var lastSaveSucceeded: Boolean = true
        private set

    // True when an unreadable file could not be set aside; saving would overwrite it.
    private var savesBlocked = false

    init {
        val raw = try {
            backend.loadSnapshotJson()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _loadError.value = "Could not read saved rides: ${e.message ?: "unknown error"}. " +
                "Changes will not be saved until the app is restarted."
            savesBlocked = true
            null
        }
        if (!raw.isNullOrBlank()) {
            try {
                val loaded = json.decodeFromString<List<CompleteRideRecord>>(raw)
                _ridesFlow.value = loaded.sortedByDescending { it.ride.startTimeEpochMs }
            } catch (e: SerializationException) {
                handleCorruptSnapshot(e)
            } catch (e: IllegalArgumentException) {
                handleCorruptSnapshot(e)
            }
        }
    }

    private fun handleCorruptSnapshot(cause: Exception) {
        val suffix = "corrupt-${clock.now().toEpochMilliseconds()}"
        try {
            backend.quarantineCorruptSnapshot(suffix)
            _loadError.value = "Saved rides could not be read (${cause.message ?: "invalid data"}). " +
                "The file was kept as a copy named like rides.$suffix.json and the app started with no rides."
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            savesBlocked = true
            _loadError.value = "Saved rides could not be read and the file could not be set aside " +
                "(${e.message ?: "unknown error"}). Changes will not be saved until the app is restarted."
        }
    }

    fun getAllRides(): List<CompleteRideRecord> = _ridesFlow.value

    fun getRideById(rideId: String): CompleteRideRecord? =
        _ridesFlow.value.firstOrNull { it.ride.rideId == rideId }

    fun insertOrReplaceRide(record: CompleteRideRecord) {
        val updated = _ridesFlow.value
            .filterNot { it.ride.rideId == record.ride.rideId } + record
        updateAndPersistReporting(updated)
    }

    fun insertOrReplaceRides(records: List<CompleteRideRecord>) {
        try {
            replaceRidesOrThrow(records)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            reportSaveFailure(e)
        }
    }

    /** Like [insertOrReplaceRides], but a failure to save is thrown and the rides are left unchanged. */
    fun replaceRidesOrThrow(records: List<CompleteRideRecord>) {
        if (records.isEmpty()) return
        val incomingIds = records.map { it.ride.rideId }.toSet()
        val updated = _ridesFlow.value
            .filterNot { it.ride.rideId in incomingIds } + records
        persistThenPublish(updated)
    }

    fun deleteRide(rideId: String) {
        val updated = _ridesFlow.value.filterNot { it.ride.rideId == rideId }
        updateAndPersistReporting(updated)
    }

    fun clearAll() {
        updateAndPersistReporting(emptyList())
    }

    /**
     * Resolves an ambiguous Express vs. Local passive ride when the user taps Confirm
     * in the "Needs Review" banner or notification.
     */
    fun confirmAmbiguousRide(
        rideId: String,
        confirmedRouteId: String,
        confirmedIsExpress: Boolean,
        graphEngine: SubwayGraphEngine
    ): CompleteRideRecord? {
        val existing = getRideById(rideId) ?: return null
        val legRequests = existing.legs.mapIndexed { idx, leg ->
            if (idx == 0) {
                TripLegRequest(
                    fromStationId = leg.fromStationId,
                    toStationId = leg.toStationId,
                    preferredRouteId = confirmedRouteId,
                    forceExpress = confirmedIsExpress
                )
            } else {
                TripLegRequest(
                    fromStationId = leg.fromStationId,
                    toStationId = leg.toStationId,
                    preferredRouteId = leg.routeId,
                    forceExpress = leg.isExpress
                )
            }
        }
        val resolved = graphEngine.resolveTrip(
            legs = legRequests,
            timestampEpochMs = existing.ride.startTimeEpochMs,
            actualDurationMinutes = existing.ride.durationMinutes
        )
        val updatedRecord = buildCompleteRideRecord(
            rideId = existing.ride.rideId,
            startTimeEpochMs = existing.ride.startTimeEpochMs,
            endTimeEpochMs = existing.ride.endTimeEpochMs,
            source = existing.ride.source,
            resolved = resolved.copy(isAmbiguous = false, ambiguousCandidates = emptyList()),
            graphEngine = graphEngine,
            notes = existing.ride.notes
        )
        insertOrReplaceRide(updatedRecord)
        return updatedRecord
    }

    private fun updateAndPersistReporting(records: List<CompleteRideRecord>) {
        try {
            persistThenPublish(records)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            reportSaveFailure(e)
        }
    }

    // Saves first and publishes only on success, so memory never holds rides that were not saved.
    // While saves are blocked nothing can be saved, so nothing is published either.
    private fun persistThenPublish(records: List<CompleteRideRecord>) {
        if (savesBlocked) {
            lastSaveSucceeded = false
            throw IllegalStateException(SAVES_BLOCKED_MESSAGE)
        }
        val sorted = records.sortedByDescending { it.ride.startTimeEpochMs }
        try {
            backend.saveSnapshotJson(json.encodeToString(sorted))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            lastSaveSucceeded = false
            throw e
        }
        lastSaveSucceeded = true
        if (_loadError.value?.startsWith(SAVE_ERROR_PREFIX) == true) _loadError.value = null
        _ridesFlow.value = sorted
    }

    private fun reportSaveFailure(cause: Exception) {
        lastSaveSucceeded = false
        _loadError.value = "$SAVE_ERROR_PREFIX ${cause.message?.removeSuffix(".") ?: "unknown error"}. The change was not applied."
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
            forceNeedsReview: Boolean? = null
        ): CompleteRideRecord {
            val originId = resolved.legs.firstOrNull()?.fromStationId.orEmpty()
            val destId = resolved.legs.lastOrNull()?.toStationId.orEmpty()
            val durationMin = if (endTimeEpochMs > startTimeEpochMs) {
                ((endTimeEpochMs - startTimeEpochMs) / 60000.0 * 10.0).roundToInt() / 10.0
            } else {
                (resolved.totalDistanceMiles / 18.0 * 60.0 * 10.0).roundToInt() / 10.0
            }

            val rideEntity = RideEntity(
                rideId = rideId,
                startTimeEpochMs = startTimeEpochMs,
                endTimeEpochMs = if (endTimeEpochMs > startTimeEpochMs) endTimeEpochMs else startTimeEpochMs + (durationMin * 60000).toLong(),
                originStationId = originId,
                destinationStationId = destId,
                totalDistanceMiles = resolved.totalDistanceMiles,
                durationMinutes = durationMin,
                source = source,
                needsReview = forceNeedsReview ?: resolved.isAmbiguous,
                ambiguousCandidates = resolved.ambiguousCandidates,
                notes = notes
            )

            val legEntities = resolved.legs.map { leg ->
                RideLegEntity(
                    legId = "${rideId}_leg_${leg.legIndex}",
                    rideId = rideId,
                    legIndex = leg.legIndex,
                    routeId = leg.routeId,
                    isExpress = leg.isExpress,
                    servicePeriod = leg.servicePeriod,
                    fromStationId = leg.fromStationId,
                    toStationId = leg.toStationId,
                    distanceMiles = leg.distanceMiles
                )
            }

            val segmentEntities = resolved.legs.flatMap { leg ->
                leg.segmentIds.mapNotNull { segId ->
                    val seg = graphEngine.getSegment(segId) ?: return@mapNotNull null
                    RideSegmentEntity(
                        rideId = rideId,
                        legIndex = leg.legIndex,
                        segmentId = segId,
                        routeId = leg.routeId,
                        distanceMiles = seg.distanceMiles
                    )
                }
            }

            val stopEntities = resolved.stationStops.map { stop ->
                RideStationStopEntity(
                    rideId = rideId,
                    stationId = stop.stationId,
                    complexId = stop.complexId,
                    stopRole = stop.role,
                    legIndex = stop.legIndex
                )
            }

            return CompleteRideRecord(
                ride = rideEntity,
                legs = legEntities,
                segments = segmentEntities,
                stationStops = stopEntities
            )
        }
    }
}
