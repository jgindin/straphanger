package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.AmbiguousRouteCandidate
import com.straphanger.app.data.gtfs.ServicePeriod
import com.straphanger.app.data.gtfs.StopRole
import kotlinx.serialization.Serializable

@Serializable
enum class RideSource(val displayLabel: String) {
    TIMELINE_IMPORT("Google Maps Timeline"),
    GMAPS_NOTIFICATION("Google Maps Live Nav"),
    GMAPS_SHARE("Shared from Google Maps"),
    PASSIVE_GPS("Passive Station Detection"),
    MANUAL("Manual Log")
}

@Serializable
data class RideEntity(
    val rideId: String,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val originStationId: String,
    val destinationStationId: String,
    val totalDistanceMiles: Double,
    val durationMinutes: Double,
    val source: RideSource,
    val needsReview: Boolean = false,
    val ambiguousCandidates: List<AmbiguousRouteCandidate> = emptyList(),
    val syncedToCloud: Boolean = false,
    val notes: String = ""
)

@Serializable
data class RideLegEntity(
    val legId: String,
    val rideId: String,
    val legIndex: Int,
    val routeId: String,
    val isExpress: Boolean,
    val servicePeriod: ServicePeriod,
    val fromStationId: String,
    val toStationId: String,
    val distanceMiles: Double
)

@Serializable
data class RideSegmentEntity(
    val rideId: String,
    val legIndex: Int,
    val segmentId: String,
    val routeId: String,
    val distanceMiles: Double
)

@Serializable
data class RideStationStopEntity(
    val rideId: String,
    val stationId: String,
    val complexId: String,
    val stopRole: StopRole,
    val legIndex: Int
)

@Serializable
data class CompleteRideRecord(
    val ride: RideEntity,
    val legs: List<RideLegEntity>,
    val segments: List<RideSegmentEntity>,
    val stationStops: List<RideStationStopEntity>
)
