package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import kotlinx.serialization.Serializable

@Serializable
data class ActiveGeofenceSpec(
    val geofenceId: String,
    val complexId: String?,
    val stationId: String?,
    val displayName: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
    val isMacroAnchor: Boolean = false
)

@Serializable
data class GeofenceWindowSnapshot(
    val anchorLat: Double,
    val anchorLng: Double,
    val activeGeofences: List<ActiveGeofenceSpec>
)

/**
 * Solves Adversarial Vulnerability #1:
 * iOS CoreLocation strictly enforces a 20-region limit per app (and Android enforces 100).
 * DynamicGeofenceWindowManager computes at most 18 nearest station complexes + 1 macro anchor
 * geofence (19 total, strictly <= 20) and dynamically refreshes the window when the user crosses
 * the macro anchor boundary.
 */
class DynamicGeofenceWindowManager(
    private val graphEngine: SubwayGraphEngine,
    val maxStationComplexes: Int = 18,
    val stationRadiusMeters: Double = 160.0,
    val macroAnchorRadiusMeters: Double = 1200.0
) {
    private var currentWindow: GeofenceWindowSnapshot? = null

    fun computeRollingWindow(userLat: Double, userLng: Double): GeofenceWindowSnapshot {
        val representativeStationsByComplex = graphEngine.network.stations
            .groupBy { it.complexId }
            .values
            .map { it.first() }

        val nearestComplexStations = representativeStationsByComplex
            .map { st ->
                val distMiles = NycSubwayNetworkCatalog.haversineMiles(userLat, userLng, st.lat, st.lng)
                st to distMiles
            }
            .sortedBy { it.second }
            .take(maxStationComplexes)
            .map { (st, _) ->
                ActiveGeofenceSpec(
                    geofenceId = stationGeofenceId(st.complexId),
                    complexId = st.complexId,
                    stationId = st.id,
                    displayName = st.complexName,
                    lat = st.lat,
                    lng = st.lng,
                    radiusMeters = stationRadiusMeters,
                    isMacroAnchor = false
                )
            }

        val macroAnchor = ActiveGeofenceSpec(
            geofenceId = MACRO_ANCHOR_ID,
            complexId = null,
            stationId = null,
            displayName = "Rolling Geofence Window Anchor",
            lat = userLat,
            lng = userLng,
            radiusMeters = macroAnchorRadiusMeters,
            isMacroAnchor = true
        )

        val snapshot = GeofenceWindowSnapshot(
            anchorLat = userLat,
            anchorLng = userLng,
            activeGeofences = nearestComplexStations + macroAnchor
        )
        currentWindow = snapshot
        return snapshot
    }

    fun shouldRefreshWindow(newLat: Double, newLng: Double): Boolean {
        val existing = currentWindow ?: return true
        val movedMiles = NycSubwayNetworkCatalog.haversineMiles(
            existing.anchorLat,
            existing.anchorLng,
            newLat,
            newLng
        )
        val movedMeters = movedMiles * 1609.34
        return movedMeters >= (macroAnchorRadiusMeters * 0.7)
    }

    companion object {
        const val MACRO_ANCHOR_ID = "ANCHOR_MACRO_WINDOW"
        const val IOS_MAX_REGION_LIMIT = 20
        private const val STATION_GEOFENCE_PREFIX = "ST_"

        fun stationGeofenceId(complexId: String): String = STATION_GEOFENCE_PREFIX + complexId

        /** The station complex a station geofence covers, or null for the macro anchor and unknown IDs. */
        fun complexIdForGeofence(geofenceId: String): String? =
            if (geofenceId.startsWith(STATION_GEOFENCE_PREFIX)) geofenceId.removePrefix(STATION_GEOFENCE_PREFIX) else null
    }
}

/**
 * Reconstructs passive subway rides from station geofence entries.
 *
 * Entering a station complex starts a candidate. Entering a different complex ends it, and a ride is
 * recorded only if the trip took 2 to 120 minutes and the straight-line distance divided by the
 * duration is at least 6 mph. Otherwise the second complex becomes the new candidate. Entering the
 * candidate's own complex again moves the candidate's start time to the new entry.
 */
class PassiveRideReconstructor(
    private val graphEngine: SubwayGraphEngine,
    private val dao: SubwayDao
) {
    var activeCandidateComplexId: String? = null
        private set
    private var activeCandidateTimeMs: Long = 0L

    /** Returns the ride recorded by this entry, or null when none was recorded. */
    fun onStationGeofenceEntered(complexId: String, timestampEpochMs: Long): CompleteRideRecord? {
        val boardComplex = activeCandidateComplexId
        if (boardComplex == null || boardComplex == complexId) {
            startCandidate(complexId, timestampEpochMs)
            return null
        }
        val boardTimeMs = activeCandidateTimeMs
        val record = plausibleRide(boardComplex, complexId, boardTimeMs, timestampEpochMs)
        if (record == null) {
            startCandidate(complexId, timestampEpochMs)
            return null
        }
        activeCandidateComplexId = null
        dao.insertOrReplaceRide(record)
        return if (dao.lastSaveSucceeded) record else null
    }

    private fun startCandidate(complexId: String, timestampEpochMs: Long) {
        activeCandidateComplexId = complexId
        activeCandidateTimeMs = timestampEpochMs
    }

    private fun plausibleRide(
        boardComplex: String,
        exitComplex: String,
        startMs: Long,
        endMs: Long
    ): CompleteRideRecord? {
        val boardStations = graphEngine.getStationsInComplex(boardComplex)
        val exitStations = graphEngine.getStationsInComplex(exitComplex)
        val board = boardStations.firstOrNull() ?: return null
        val exit = exitStations.firstOrNull() ?: return null

        val durationMin = (endMs - startMs) / 60_000.0
        if (durationMin < MIN_RIDE_MINUTES || durationMin > MAX_RIDE_MINUTES) return null
        val miles = NycSubwayNetworkCatalog.haversineMiles(board.lat, board.lng, exit.lat, exit.lng)
        if (miles / (durationMin / 60.0) < MIN_SPEED_MPH) return null

        // Pick the platform pair with the fewest legs, then the shortest distance.
        val resolved = boardStations.flatMap { from ->
            exitStations.map { to ->
                graphEngine.resolveOriginToDestinationAuto(
                    originStationId = from.id,
                    destinationStationId = to.id,
                    timestampEpochMs = startMs,
                    actualDurationMinutes = durationMin
                )
            }
        }
            .filter { it.legs.isNotEmpty() }
            .minWithOrNull(compareBy({ it.legs.size }, { it.totalDistanceMiles }))
            ?: return null

        return SubwayDao.buildCompleteRideRecord(
            rideId = "passive_$startMs",
            startTimeEpochMs = startMs,
            endTimeEpochMs = endMs,
            source = RideSource.PASSIVE_GPS,
            resolved = resolved,
            graphEngine = graphEngine
        )
    }

    companion object {
        const val MIN_RIDE_MINUTES = 2.0
        const val MAX_RIDE_MINUTES = 120.0
        const val MIN_SPEED_MPH = 6.0
    }
}
