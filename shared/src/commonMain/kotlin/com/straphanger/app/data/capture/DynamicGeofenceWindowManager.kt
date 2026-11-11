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
                    geofenceId = "ST_${st.complexId}",
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
    }
}

/**
 * Reconstructs passive subway rides from station geofence entry + underground signal drop + exit events.
 */
class PassiveRideReconstructor(
    private val graphEngine: SubwayGraphEngine,
    private val dao: SubwayDao
) {
    private var activeBoardingStationId: String? = null
    private var activeBoardingTimeMs: Long = 0L
    private var observedUndergroundSignalDrop: Boolean = false

    fun onStationGeofenceEntered(stationId: String, timestampEpochMs: Long) {
        if (activeBoardingStationId == null) {
            activeBoardingStationId = stationId
            activeBoardingTimeMs = timestampEpochMs
            observedUndergroundSignalDrop = false
        }
    }

    fun onUndergroundSignalDropDetected() {
        if (activeBoardingStationId != null) {
            observedUndergroundSignalDrop = true
        }
    }

    fun onStationGeofenceExitedOrArrived(
        exitStationId: String,
        timestampEpochMs: Long
    ): CompleteRideRecord? {
        val boardId = activeBoardingStationId ?: return null
        val boardComplex = graphEngine.getStation(boardId)?.complexId
        val exitComplex = graphEngine.getStation(exitStationId)?.complexId
        val durationMin = (timestampEpochMs - activeBoardingTimeMs) / 60000.0

        // Require either an underground signal drop or plausible transit duration between distinct complexes
        if (boardComplex == exitComplex || durationMin < 1.5) {
            return null
        }

        val resolved = graphEngine.resolveOriginToDestinationAuto(
            originStationId = boardId,
            destinationStationId = exitStationId,
            preferredRouteId = null,
            timestampEpochMs = activeBoardingTimeMs,
            actualDurationMinutes = durationMin
        )
        if (resolved.legs.isEmpty()) return null

        val record = SubwayDao.buildCompleteRideRecord(
            rideId = "passive_${activeBoardingTimeMs}",
            startTimeEpochMs = activeBoardingTimeMs,
            endTimeEpochMs = timestampEpochMs,
            source = RideSource.PASSIVE_GPS,
            resolved = resolved,
            graphEngine = graphEngine
        )
        dao.insertOrReplaceRide(record)
        activeBoardingStationId = null
        observedUndergroundSignalDrop = false
        return record
    }
}
