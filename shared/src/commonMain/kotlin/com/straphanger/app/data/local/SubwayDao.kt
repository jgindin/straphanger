package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.ResolvedTripResult
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.TripLegRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

interface RidePersistenceBackend {
    fun loadSnapshotJson(): String?
    fun saveSnapshotJson(json: String)
}

class InMemoryPersistenceBackend(initialJson: String? = null) : RidePersistenceBackend {
    private var stored: String? = initialJson
    override fun loadSnapshotJson(): String? = stored
    override fun saveSnapshotJson(json: String) {
        stored = json
    }
}

class SubwayDao(
    private val backend: RidePersistenceBackend = InMemoryPersistenceBackend()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _ridesFlow = MutableStateFlow<List<CompleteRideRecord>>(emptyList())
    val ridesFlow: StateFlow<List<CompleteRideRecord>> = _ridesFlow.asStateFlow()

    init {
        val raw = backend.loadSnapshotJson()
        if (!raw.isNullOrBlank()) {
            runCatching {
                json.decodeFromString<List<CompleteRideRecord>>(raw)
            }.onSuccess { loaded ->
                _ridesFlow.value = loaded.sortedByDescending { it.ride.startTimeEpochMs }
            }
        }
    }

    fun getAllRides(): List<CompleteRideRecord> = _ridesFlow.value

    fun getRideById(rideId: String): CompleteRideRecord? =
        _ridesFlow.value.firstOrNull { it.ride.rideId == rideId }

    fun insertOrReplaceRide(record: CompleteRideRecord) {
        val updated = _ridesFlow.value
            .filterNot { it.ride.rideId == record.ride.rideId } + record
        updateAndPersist(updated)
    }

    fun insertOrReplaceRides(records: List<CompleteRideRecord>) {
        if (records.isEmpty()) return
        val incomingIds = records.map { it.ride.rideId }.toSet()
        val updated = _ridesFlow.value
            .filterNot { it.ride.rideId in incomingIds } + records
        updateAndPersist(updated)
    }

    fun deleteRide(rideId: String) {
        val updated = _ridesFlow.value.filterNot { it.ride.rideId == rideId }
        updateAndPersist(updated)
    }

    fun clearAll() {
        updateAndPersist(emptyList())
    }

    fun getUnsyncedRides(): List<CompleteRideRecord> =
        _ridesFlow.value.filter { !it.ride.syncedToCloud }

    fun markRidesSynced(rideIds: Set<String>) {
        if (rideIds.isEmpty()) return
        val updated = _ridesFlow.value.map { rec ->
            if (rec.ride.rideId in rideIds) {
                rec.copy(ride = rec.ride.copy(syncedToCloud = true))
            } else {
                rec
            }
        }
        updateAndPersist(updated)
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

    private fun updateAndPersist(records: List<CompleteRideRecord>) {
        val sorted = records.sortedByDescending { it.ride.startTimeEpochMs }
        _ridesFlow.value = sorted
        backend.saveSnapshotJson(json.encodeToString(sorted))
    }

    companion object {
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
                syncedToCloud = false,
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
