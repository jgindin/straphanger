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
 * Reconstructs passive subway rides from station geofence entries by chaining stations.
 *
 * A chain has an origin and a last station. Entering a complex other than the last one extends the
 * chain when the hop from the last station is at least 6 mph and came within 30 minutes. Otherwise
 * the chain closes and a new one starts at the entered complex. A chain also closes when
 * [idleCloseMs] passes with no new station entry ([closeIfIdle], or the next entry).
 *
 * Closing records a ride from origin to last only if they are different complexes, the trip took 2
 * to 120 minutes, and the straight-line speed from origin to last is at least 6 mph.
 */
class PassiveRideReconstructor(
    private val graphEngine: SubwayGraphEngine,
    private val dao: SubwayDao,
    val idleCloseMs: Long = DEFAULT_IDLE_CLOSE_MS
) {
    private class Visit(val complexId: String, val timeMs: Long)

    private var origin: Visit? = null
    private var last: Visit? = null

    val chainOriginComplexId: String? get() = origin?.complexId
    val chainOriginTimeMs: Long? get() = origin?.timeMs
    val chainLastComplexId: String? get() = last?.complexId
    val chainLastTimeMs: Long? get() = last?.timeMs

    /** Returns the ride recorded because this entry closed the previous chain, or null. */
    fun onStationGeofenceEntered(complexId: String, timestampEpochMs: Long): CompleteRideRecord? {
        val previous = last
        if (previous == null) {
            startChain(complexId, timestampEpochMs)
            return null
        }
        val gapMs = timestampEpochMs - previous.timeMs
        if (gapMs < 0) return null // older than the chain; out-of-order delivery
        if (gapMs >= idleCloseMs) {
            val record = close()
            startChain(complexId, timestampEpochMs)
            return record
        }
        if (complexId == previous.complexId) {
            // Still at the only station of the chain: the latest entry is closest to boarding.
            if (origin === previous) startChain(complexId, timestampEpochMs)
            return null
        }
        val hopMph = mph(previous.complexId, complexId, gapMs)
        if (hopMph != null && hopMph >= MIN_SPEED_MPH && gapMs <= MAX_HOP_MS) {
            last = Visit(complexId, timestampEpochMs)
            return null
        }
        val record = close()
        startChain(complexId, timestampEpochMs)
        return record
    }

    /** Closes the chain if [idleCloseMs] has passed since its last entry; returns the recorded ride, or null. */
    fun closeIfIdle(nowEpochMs: Long): CompleteRideRecord? {
        val previous = last ?: return null
        return if (nowEpochMs - previous.timeMs >= idleCloseMs) close() else null
    }

    private fun startChain(complexId: String, timestampEpochMs: Long) {
        val visit = Visit(complexId, timestampEpochMs)
        origin = visit
        last = visit
    }

    private fun close(): CompleteRideRecord? {
        val from = origin
        val to = last
        origin = null
        last = null
        if (from == null || to == null || from.complexId == to.complexId) return null
        val durationMs = to.timeMs - from.timeMs
        val durationMin = durationMs / 60_000.0
        if (durationMin < MIN_RIDE_MINUTES || durationMin > MAX_RIDE_MINUTES) return null
        val overallMph = mph(from.complexId, to.complexId, durationMs) ?: return null
        if (overallMph < MIN_SPEED_MPH) return null
        val record = buildRide(from, to, durationMin) ?: return null
        dao.insertOrReplaceRide(record)
        return if (dao.lastSaveSucceeded) record else null
    }

    /** Straight-line speed between the complexes' first stations; null for an unknown complex. */
    private fun mph(fromComplex: String, toComplex: String, durationMs: Long): Double? {
        val a = graphEngine.getStationsInComplex(fromComplex).firstOrNull() ?: return null
        val b = graphEngine.getStationsInComplex(toComplex).firstOrNull() ?: return null
        val miles = NycSubwayNetworkCatalog.haversineMiles(a.lat, a.lng, b.lat, b.lng)
        if (durationMs <= 0) return Double.POSITIVE_INFINITY
        return miles / (durationMs / 3_600_000.0)
    }

    private fun buildRide(from: Visit, to: Visit, durationMin: Double): CompleteRideRecord? {
        val boardStations = graphEngine.getStationsInComplex(from.complexId)
        val exitStations = graphEngine.getStationsInComplex(to.complexId)
        // Pick the platform pair with the fewest legs, then the shortest distance.
        val resolved = boardStations.flatMap { a ->
            exitStations.map { b ->
                graphEngine.resolveOriginToDestinationAuto(
                    originStationId = a.id,
                    destinationStationId = b.id,
                    timestampEpochMs = from.timeMs,
                    actualDurationMinutes = durationMin
                )
            }
        }
            .filter { it.legs.isNotEmpty() }
            .minWithOrNull(compareBy({ it.legs.size }, { it.totalDistanceMiles }))
            ?: return null

        return SubwayDao.buildCompleteRideRecord(
            rideId = "passive_${from.timeMs}",
            startTimeEpochMs = from.timeMs,
            endTimeEpochMs = to.timeMs,
            source = RideSource.PASSIVE_GPS,
            resolved = resolved,
            graphEngine = graphEngine
        )
    }

    companion object {
        const val MIN_RIDE_MINUTES = 2.0
        const val MAX_RIDE_MINUTES = 120.0
        const val MIN_SPEED_MPH = 6.0
        const val MAX_HOP_MS = 30 * 60_000L
        const val DEFAULT_IDLE_CLOSE_MS = 15 * 60_000L
    }
}
