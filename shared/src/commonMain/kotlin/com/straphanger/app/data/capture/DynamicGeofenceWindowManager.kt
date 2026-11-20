package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.data.local.flagOverlappingRides
import kotlin.math.abs
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
  val isMacroAnchor: Boolean = false,
)

@Serializable
data class GeofenceWindowSnapshot(
  val anchorLat: Double,
  val anchorLng: Double,
  val activeGeofences: List<ActiveGeofenceSpec>,
)

/**
 * Chooses which geofences to register. iOS CoreLocation allows 20 monitored regions per app and
 * Android allows 100. This class computes at most 18 nearest station complexes plus one anchor
 * geofence (19 in total) and recomputes the window when the user leaves the anchor.
 */
class DynamicGeofenceWindowManager(
  private val graphEngine: SubwayGraphEngine,
  val maxStationComplexes: Int = 18,
  val stationRadiusMeters: Double = 160.0,
  val macroAnchorRadiusMeters: Double = 1200.0,
) {
  private var currentWindow: GeofenceWindowSnapshot? = null

  fun computeRollingWindow(userLat: Double, userLng: Double): GeofenceWindowSnapshot {
    val representativeStationsByComplex =
      graphEngine.network.stations.groupBy { it.complexId }.values.map { it.first() }

    val nearestComplexStations =
      representativeStationsByComplex
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
            isMacroAnchor = false,
          )
        }

    val macroAnchor =
      ActiveGeofenceSpec(
        geofenceId = MACRO_ANCHOR_ID,
        complexId = null,
        stationId = null,
        displayName = "Rolling Geofence Window Anchor",
        lat = userLat,
        lng = userLng,
        radiusMeters = macroAnchorRadiusMeters,
        isMacroAnchor = true,
      )

    val snapshot =
      GeofenceWindowSnapshot(
        anchorLat = userLat,
        anchorLng = userLng,
        activeGeofences = nearestComplexStations + macroAnchor,
      )
    currentWindow = snapshot
    return snapshot
  }

  fun shouldRefreshWindow(newLat: Double, newLng: Double): Boolean {
    val existing = currentWindow ?: return true
    val movedMiles =
      NycSubwayNetworkCatalog.haversineMiles(
        existing.anchorLat,
        existing.anchorLng,
        newLat,
        newLng,
      )
    val movedMeters = movedMiles * 1609.34
    return movedMeters >= (macroAnchorRadiusMeters * ANCHOR_REFRESH_FRACTION)
  }

  companion object {
    const val MACRO_ANCHOR_ID = "ANCHOR_MACRO_WINDOW"
    const val IOS_MAX_REGION_LIMIT = 20
    private const val STATION_GEOFENCE_PREFIX = "ST_"

    fun stationGeofenceId(complexId: String): String = STATION_GEOFENCE_PREFIX + complexId

    /**
     * The station complex a station geofence covers, or null for the macro anchor and unknown IDs.
     */
    fun complexIdForGeofence(geofenceId: String): String? =
      if (geofenceId.startsWith(STATION_GEOFENCE_PREFIX))
        geofenceId.removePrefix(STATION_GEOFENCE_PREFIX)
      else null
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
 * A recorded ride needs review when its line is ambiguous or when it needed a transfer, since the
 * chain holds only the first and last station.
 *
 * Closing records a ride from origin to last only if they are different complexes, the trip took 2
 * to 120 minutes, and the effective distance is at least 0.3 miles and at least 6 mph. The
 * effective distance is the straight-line distance between the stations less two geofence radii,
 * because a walker is detected at the facing edges of the two geofences. The hop check uses the
 * plain centre-to-centre distance.
 */
class PassiveRideReconstructor(
  private val graphEngine: SubwayGraphEngine,
  private val dao: SubwayDao,
  val idleCloseMs: Long = DEFAULT_IDLE_CLOSE_MS,
  /** Radius of the station geofences; [DynamicGeofenceWindowManager.stationRadiusMeters]. */
  stationRadiusMeters: Double = 160.0,
) {
  private val stationRadiusMiles = stationRadiusMeters / METERS_PER_MILE

  private var origin: Visit? = null
  private var last: Visit? = null

  /** When the last station of the chain was left, so an entry at the same fix can be ignored. */
  private var departedAtMs: Long? = null

  val chainOriginComplexId: String?
    get() = origin?.complexId

  val chainOriginTimeMs: Long?
    get() = origin?.timeMs

  val chainLastComplexId: String?
    get() = last?.complexId

  val chainLastTimeMs: Long?
    get() = last?.timeMs

  /**
   * Start time of the oldest chain whose ride could not be saved, or null. Its station entries must
   * be kept so the ride is recorded again on the next start.
   */
  var unsavedChainOriginTimeMs: Long? = null
    private set

  /** Returns the ride recorded because this entry closed the previous chain, or null. */
  fun onStationGeofenceEntered(complexId: String, timestampEpochMs: Long): CompleteRideRecord? {
    val previous = last
    return when {
      previous == null -> {
        startChain(complexId, timestampEpochMs)
        null
      }
      // Older than the chain: out-of-order delivery.
      timestampEpochMs < previous.timeMs -> null
      // Seen at the fix that reported leaving the last station: the train was already moving, so
      // this station is only where the late fix landed, not where the ride began.
      complexId != previous.complexId && isSameFixAsDeparture(timestampEpochMs) -> null
      timestampEpochMs - previous.timeMs >= idleCloseMs ->
        closeAndStartChain(complexId, timestampEpochMs)
      complexId == previous.complexId -> {
        // Still at the only station of the chain: the latest entry is closest to boarding.
        if (origin === previous) startChain(complexId, timestampEpochMs)
        null
      }
      graphEngine.isTransitHop(previous, complexId, timestampEpochMs - previous.timeMs) -> {
        last = Visit(complexId, timestampEpochMs)
        null
      }
      else -> closeAndStartChain(complexId, timestampEpochMs)
    }
  }

  private fun isSameFixAsDeparture(timestampEpochMs: Long): Boolean =
    departedAtMs?.let { abs(timestampEpochMs - it) <= SAME_FIX_MS } == true

  /**
   * Handles leaving a station geofence. Platform fixes underground are sparse, so an exit is often
   * reported only after the train has gone on. The station left is where the ride began (or ended),
   * so a chain still at one station takes the exit as its boarding time, a chain that has dwelt at
   * its last station for [DWELL_SPLIT_MS] is closed there and a new ride begins, and an exit from
   * any other station is ignored. Returns the ride recorded by closing a chain, or null.
   */
  fun onStationGeofenceExited(complexId: String, timestampEpochMs: Long): CompleteRideRecord? {
    val previous = last
    val record =
      when {
        previous == null -> {
          startChain(complexId, timestampEpochMs)
          null
        }
        complexId != previous.complexId || timestampEpochMs < previous.timeMs -> return null
        origin === previous -> {
          startChain(complexId, timestampEpochMs)
          null
        }
        timestampEpochMs - previous.timeMs >= DWELL_SPLIT_MS ->
          closeAndStartChain(complexId, timestampEpochMs)
        else -> null
      }
    departedAtMs = timestampEpochMs
    return record
  }

  private fun closeAndStartChain(complexId: String, timestampEpochMs: Long): CompleteRideRecord? {
    val record = close()
    startChain(complexId, timestampEpochMs)
    return record
  }

  /**
   * Closes the chain if [idleCloseMs] has passed since its last entry; returns the recorded ride,
   * or null.
   */
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
    departedAtMs = null
    return if (from != null && to != null) plausibleRide(from, to)?.let { saved(it, from) }
    else null
  }

  /** The ride from [from] to [to], or null when the chain does not look like a subway ride. */
  private fun plausibleRide(from: Visit, to: Visit): CompleteRideRecord? {
    val durationMs = to.timeMs - from.timeMs
    val durationMin = durationMs / 60_000.0
    val plausible =
      from.complexId != to.complexId &&
        durationMin in MIN_RIDE_MINUTES..MAX_RIDE_MINUTES &&
        coversRideDistance(from.complexId, to.complexId, durationMs)
    return if (plausible) buildRide(from, to, durationMin) else null
  }

  /**
   * True when the distance between the complexes, less the station radii, is long enough and was
   * covered fast enough for a ride.
   */
  private fun coversRideDistance(
    fromComplex: String,
    toComplex: String,
    durationMs: Long,
  ): Boolean {
    val centreMiles = graphEngine.complexMiles(fromComplex, toComplex) ?: return false
    val effectiveMiles = maxOf(0.0, centreMiles - 2 * stationRadiusMiles)
    return effectiveMiles >= MIN_EFFECTIVE_MILES &&
      effectiveMiles / (durationMs / 3_600_000.0) >= MIN_SPEED_MPH
  }

  /**
   * Saves [record]; returns it, or null when the save failed and the chain must be replayed, or
   * when a replay found the ride already stored.
   */
  private fun saved(record: CompleteRideRecord, from: Visit): CompleteRideRecord? {
    // A replayed chain whose ride is already stored: keep the stored ride, which the user may have
    // edited, and do not report it again.
    if (dao.getAllRides().any { it.ride.rideId == record.ride.rideId }) {
      if (unsavedChainOriginTimeMs == from.timeMs) unsavedChainOriginTimeMs = null
      return null
    }
    dao.insertOrReplaceRide(record)
    if (dao.lastSaveSucceeded) dao.flagOverlappingRides(record.ride.rideId)
    if (!dao.lastSaveSucceeded) {
      unsavedChainOriginTimeMs = minOf(unsavedChainOriginTimeMs ?: from.timeMs, from.timeMs)
    } else if (unsavedChainOriginTimeMs == from.timeMs) {
      // The replay of the oldest unsaved chain was saved; its entries need not be kept.
      unsavedChainOriginTimeMs = null
    }
    return record.takeIf { dao.lastSaveSucceeded }
  }

  private fun buildRide(from: Visit, to: Visit, durationMin: Double): CompleteRideRecord? {
    val boardStations = graphEngine.getStationsInComplex(from.complexId)
    val exitStations = graphEngine.getStationsInComplex(to.complexId)
    // Pick the platform pair with the fewest legs, then the shortest distance.
    val resolved =
      boardStations
        .flatMap { a ->
          exitStations.map { b ->
            graphEngine.resolveOriginToDestinationAuto(
              originStationId = a.id,
              destinationStationId = b.id,
              timestampEpochMs = from.timeMs,
              actualDurationMinutes = durationMin,
            )
          }
        }
        .filter { it.legs.isNotEmpty() }
        .minWithOrNull(compareBy({ it.legs.size }, { it.totalDistanceMiles })) ?: return null

    return SubwayDao.buildCompleteRideRecord(
      rideId = "passive_${from.timeMs}",
      startTimeEpochMs = from.timeMs,
      endTimeEpochMs = to.timeMs,
      source = RideSource.PASSIVE_GPS,
      resolved = resolved,
      graphEngine = graphEngine,
      // The chain knows only where the ride began and ended. A transfer between lines is a guess,
      // so the user is asked to confirm the route.
      forceNeedsReview = resolved.isAmbiguous || resolved.legs.size > 1,
    )
  }

  companion object {
    const val MIN_RIDE_MINUTES = 2.0
    const val MAX_RIDE_MINUTES = 120.0
    const val MIN_SPEED_MPH = 6.0
    const val MIN_EFFECTIVE_MILES = 0.3
    private const val METERS_PER_MILE = 1609.344
    const val MAX_HOP_MS = 30 * 60_000L
    const val DEFAULT_IDLE_CLOSE_MS = 15 * 60_000L
    /** Time at a station after which leaving it ends the ride rather than passing through. */
    const val DWELL_SPLIT_MS = 6 * 60_000L
    private const val SAME_FIX_MS = 5_000L
  }
}

private class Visit(val complexId: String, val timeMs: Long)

private fun SubwayGraphEngine.isTransitHop(
  previous: Visit,
  complexId: String,
  gapMs: Long,
): Boolean {
  val hopMph = complexMph(previous.complexId, complexId, gapMs)
  return hopMph != null &&
    hopMph >= PassiveRideReconstructor.MIN_SPEED_MPH &&
    gapMs <= PassiveRideReconstructor.MAX_HOP_MS
}

/** Straight-line miles between the complexes' first stations; null for an unknown complex. */
private fun SubwayGraphEngine.complexMiles(fromComplex: String, toComplex: String): Double? {
  val a = getStationsInComplex(fromComplex).firstOrNull()
  val b = getStationsInComplex(toComplex).firstOrNull()
  return if (a != null && b != null) {
    NycSubwayNetworkCatalog.haversineMiles(a.lat, a.lng, b.lat, b.lng)
  } else {
    null
  }
}

/** Centre-to-centre speed between the complexes; null for an unknown complex. */
private fun SubwayGraphEngine.complexMph(
  fromComplex: String,
  toComplex: String,
  durationMs: Long,
): Double? =
  complexMiles(fromComplex, toComplex)?.let { miles ->
    if (durationMs <= 0) Double.POSITIVE_INFINITY else miles / (durationMs / 3_600_000.0)
  }

/** A macro anchor is refreshed once the user has moved this fraction of its radius. */
private const val ANCHOR_REFRESH_FRACTION = 0.7
