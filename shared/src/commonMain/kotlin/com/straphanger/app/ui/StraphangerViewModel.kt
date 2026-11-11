package com.straphanger.app.ui

import com.straphanger.app.SharedRouteInbox
import com.straphanger.app.data.capture.DynamicGeofenceWindowManager
import com.straphanger.app.data.capture.GoogleMapsShareParser
import com.straphanger.app.data.capture.TimelineJsonImporter
import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.gtfs.TripLegRequest
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.data.sync.BackupExportManager
import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import com.straphanger.app.domain.stats.SubwayStatsCalculator
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MapColorMode(val label: String) {
    OFFICIAL_MTA_COLORS("MTA Line Colors"),
    FREQUENCY_HEATMAP("Frequency Heatmap")
}

enum class AppTab(val label: String) {
    MAP("Subway Map"),
    STATS("Statistics"),
    RIDES("Rides & Log"),
    SOURCES("Google Maps & Backup")
}

data class StraphangerUiState(
    val activeTab: AppTab = AppTab.MAP,
    val rides: List<CompleteRideRecord> = emptyList(),
    val stats: SubwayStatsSnapshot,
    val mapColorMode: MapColorMode = MapColorMode.OFFICIAL_MTA_COLORS,
    val selectedRouteFilter: String? = null,
    val selectedStationId: String? = null,
    val stationGroupingMode: StationGroupingMode = StationGroupingMode.INDIVIDUAL_STATIONS,
    val stationVisitFilterMode: StationVisitFilterMode = StationVisitFilterMode.STOPPED_AT,
    val isLogRideSheetOpen: Boolean = false,
    // GTFS station IDs: Bedford Av (L), 14 St-Union Sq (L), 86 St (4/5/6)
    val logOriginStationId: String = StationIds.BEDFORD_AV_L,
    val logTransferStationId: String? = StationIds.UNION_SQ_L,
    val logDestinationStationId: String = StationIds.EIGHTY_SIXTH_LEX,
    val logPreferredRouteId: String? = null,
    val logForceExpress: Boolean? = null,
    val passiveGeofenceEnabled: Boolean = true,
    val googleMapsNotificationEnabled: Boolean = true,
    val activeGeofenceCount: Int = 19,
    val statusMessage: String = "Rides are stored on this device",
    val loadError: String? = null,
    val isNetworkLoaded: Boolean = false
)

/** GTFS parent-station IDs used by the log form defaults, presets and sample rides. */
object StationIds {
    const val BEDFORD_AV_L = "L08"
    const val UNION_SQ_L = "L03"
    const val UNION_SQ_LEX = "635"
    const val EIGHTY_SIXTH_LEX = "626"
    const val TIMES_SQ_SHUTTLE = "902"
    const val GRAND_CENTRAL_SHUTTLE = "901"
    const val COLUMBUS_CIRCLE_8AV = "A24"
    const val HARLEM_125_8AV = "A15"
    const val FOURTEENTH_ST_8AV = "A31"
    const val YANKEE_STADIUM_D = "D11"
    const val ATLANTIC_AV_D = "R31"
}

class StraphangerViewModel(
    backend: RidePersistenceBackend = InMemoryPersistenceBackend(),
    private val clock: Clock = Clock.System,
    seedInitialSampleRides: Boolean = false,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main.immediate,
    networkLoader: suspend () -> SubwayNetworkData = { NycSubwayNetworkCatalog.loadBundled() }
) : ViewModel(CoroutineScope(SupervisorJob() + dispatcher)) {

    /** Services that need the subway network; null until it has loaded. */
    private class NetworkServices(val graphEngine: SubwayGraphEngine, clock: Clock) {
        val statsCalculator = SubwayStatsCalculator(graphEngine)
        val timelineImporter = TimelineJsonImporter(graphEngine)
        val shareParser = GoogleMapsShareParser(graphEngine, clock = clock)
        val geofenceManager = DynamicGeofenceWindowManager(graphEngine)
    }

    private var services: NetworkServices? = null

    /** The loaded network's engine, or null while the network is loading. */
    val graphEngine: SubwayGraphEngine? get() = services?.graphEngine

    val dao = SubwayDao(backend, clock)
    val backupManager = BackupExportManager(dao, clock)

    private val _uiState = MutableStateFlow(
        StraphangerUiState(
            rides = dao.getAllRides(),
            stats = SubwayStatsCalculator(SubwayGraphEngine(SubwayNetworkData.EMPTY)).computeStats(emptyList()),
            loadError = dao.loadError.value,
            activeGeofenceCount = 0
        )
    )
    val uiState: StateFlow<StraphangerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dao.loadError.collect { error -> _uiState.update { it.copy(loadError = error) } }
        }
        viewModelScope.launch {
            val network = try {
                networkLoader()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(statusMessage = "Could not load the subway network: ${e.message ?: "unknown error"}") }
                return@launch
            }
            onNetworkLoaded(network, seedInitialSampleRides)
        }
    }

    private fun onNetworkLoaded(network: SubwayNetworkData, seedInitialSampleRides: Boolean) {
        val loaded = NetworkServices(SubwayGraphEngine(network), clock)
        services = loaded
        _uiState.update {
            it.copy(
                isNetworkLoaded = true,
                activeGeofenceCount = loaded.geofenceManager.computeRollingWindow(40.7347, -73.9899).activeGeofences.size
            )
        }
        if (seedInitialSampleRides && dao.getAllRides().isEmpty()) {
            seedStarterNycRides()
        }
        viewModelScope.launch {
            dao.ridesFlow.collect { latestRides ->
                val latestStats = loaded.statsCalculator.computeStats(latestRides)
                _uiState.update {
                    it.copy(
                        rides = latestRides,
                        stats = latestStats
                    )
                }
            }
        }
        viewModelScope.launch {
            SharedRouteInbox.pendingSharedTexts.collect { pending ->
                if (pending.isNotEmpty()) {
                    val items = SharedRouteInbox.consumeAll()
                    for (txt in items) {
                        val parsed = loaded.shareParser.parseAndResolveSharedRoute(txt)
                        if (parsed != null) {
                            dao.insertOrReplaceRide(parsed)
                            if (dao.lastSaveSucceeded) _uiState.update {
                                it.copy(statusMessage = "Auto-captured ride from Google Maps: ${parsed.ride.originStationId} → ${parsed.ride.destinationStationId}")
                            }
                        }
                    }
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setMapColorMode(mode: MapColorMode) {
        _uiState.update { it.copy(mapColorMode = mode) }
    }

    fun setRouteFilter(routeId: String?) {
        _uiState.update {
            it.copy(selectedRouteFilter = if (it.selectedRouteFilter == routeId) null else routeId)
        }
    }

    fun selectStationOnMap(stationId: String?) {
        _uiState.update { it.copy(selectedStationId = stationId) }
    }

    fun setStationGroupingMode(mode: StationGroupingMode) {
        _uiState.update { it.copy(stationGroupingMode = mode) }
    }

    fun setStationVisitFilterMode(mode: StationVisitFilterMode) {
        _uiState.update { it.copy(stationVisitFilterMode = mode) }
    }

    fun openLogRideSheet(
        originId: String? = null,
        destinationId: String? = null,
        transferStationId: String? = null
    ) {
        _uiState.update {
            it.copy(
                isLogRideSheetOpen = true,
                logOriginStationId = originId ?: it.logOriginStationId,
                logDestinationStationId = destinationId ?: it.logDestinationStationId,
                logTransferStationId = transferStationId
            )
        }
    }

    fun closeLogRideSheet() {
        _uiState.update { it.copy(isLogRideSheetOpen = false) }
    }

    fun updateLogForm(
        originId: String = _uiState.value.logOriginStationId,
        transferId: String? = _uiState.value.logTransferStationId,
        destinationId: String = _uiState.value.logDestinationStationId,
        preferredRouteId: String? = _uiState.value.logPreferredRouteId,
        forceExpress: Boolean? = _uiState.value.logForceExpress
    ) {
        _uiState.update {
            it.copy(
                logOriginStationId = originId,
                logTransferStationId = transferId,
                logDestinationStationId = destinationId,
                logPreferredRouteId = preferredRouteId,
                logForceExpress = forceExpress
            )
        }
    }

    fun submitQuickRide(
        originStationId: String = _uiState.value.logOriginStationId,
        transferStationId: String? = _uiState.value.logTransferStationId,
        destinationStationId: String = _uiState.value.logDestinationStationId,
        preferredRouteId: String? = _uiState.value.logPreferredRouteId,
        forceExpress: Boolean? = _uiState.value.logForceExpress,
        timestampEpochMs: Long = clock.now().toEpochMilliseconds()
    ): CompleteRideRecord? {
        val graphEngine = this.graphEngine ?: return null
        if (originStationId == destinationStationId) return null

        val resolved = if (!transferStationId.isNullOrBlank() &&
            transferStationId != originStationId &&
            transferStationId != destinationStationId
        ) {
            val leg1 = graphEngine.resolveOriginToDestinationAuto(
                originStationId = originStationId,
                destinationStationId = transferStationId,
                timestampEpochMs = timestampEpochMs
            )
            val leg2 = graphEngine.resolveOriginToDestinationAuto(
                originStationId = transferStationId,
                destinationStationId = destinationStationId,
                preferredRouteId = preferredRouteId,
                forceExpress = forceExpress,
                timestampEpochMs = timestampEpochMs
            )
            val combinedRequests = (leg1.legs + leg2.legs).mapIndexed { idx, l ->
                TripLegRequest(
                    fromStationId = l.fromStationId,
                    toStationId = l.toStationId,
                    preferredRouteId = if (idx == leg1.legs.size) (preferredRouteId ?: l.routeId) else l.routeId,
                    forceExpress = if (idx == leg1.legs.size) (forceExpress ?: l.isExpress) else l.isExpress
                )
            }
            graphEngine.resolveTrip(combinedRequests, timestampEpochMs)
        } else {
            graphEngine.resolveOriginToDestinationAuto(
                originStationId = originStationId,
                destinationStationId = destinationStationId,
                preferredRouteId = preferredRouteId,
                forceExpress = forceExpress,
                timestampEpochMs = timestampEpochMs
            )
        }

        if (resolved.legs.isEmpty()) return null

        val record = SubwayDao.buildCompleteRideRecord(
            rideId = "manual_${timestampEpochMs}_${dao.getAllRides().size}",
            startTimeEpochMs = timestampEpochMs,
            endTimeEpochMs = timestampEpochMs + 18 * 60_000L,
            source = RideSource.MANUAL,
            resolved = resolved,
            graphEngine = graphEngine,
            forceNeedsReview = false
        )
        dao.insertOrReplaceRide(record)
        if (!dao.lastSaveSucceeded) return null
        _uiState.update {
            it.copy(
                isLogRideSheetOpen = false,
                statusMessage = "Logged ride (${record.ride.totalDistanceMiles} mi across ${record.legs.size} leg(s))"
            )
        }
        return record
    }

    fun simulateAmbiguousPassiveRide(): CompleteRideRecord? {
        val graphEngine = this.graphEngine ?: return null
        val ts = clock.now().toEpochMilliseconds()
        val resolved = graphEngine.resolveOriginToDestinationAuto(
            originStationId = StationIds.UNION_SQ_LEX,
            destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
            preferredRouteId = null,
            timestampEpochMs = ts,
            actualDurationMinutes = 10.5
        )
        val record = SubwayDao.buildCompleteRideRecord(
            rideId = "passive_ambig_${dao.getAllRides().size + 1}",
            startTimeEpochMs = ts,
            endTimeEpochMs = ts + 10 * 60_000L,
            source = RideSource.PASSIVE_GPS,
            resolved = resolved,
            graphEngine = graphEngine,
            forceNeedsReview = true
        )
        dao.insertOrReplaceRide(record)
        if (dao.lastSaveSucceeded) _uiState.update {
            it.copy(
                statusMessage = "Passively detected 14 St–Union Sq → 86 St • Tap to confirm 4 Express vs 6 Local"
            )
        }
        return record
    }

    fun confirmAmbiguousRide(rideId: String, routeId: String, isExpress: Boolean) {
        val graphEngine = this.graphEngine ?: return
        dao.confirmAmbiguousRide(rideId, routeId, isExpress, graphEngine)
        if (dao.lastSaveSucceeded) _uiState.update {
            it.copy(
                statusMessage = "Confirmed $routeId ${if (isExpress) "Express" else "Local"} for ride $rideId"
            )
        }
    }

    fun importSampleGoogleMapsTimeline(): Int {
        val timelineImporter = services?.timelineImporter ?: return 0
        // 6 from 86 St to 14 St-Union Sq, then A from 125 St to 59 St-Columbus Circle.
        val sampleTimelineJson = """
            {
              "semanticSegments": [
                {
                  "startTime": "2026-11-05T08:30:00.000-05:00",
                  "endTime": "2026-11-05T08:48:00.000-05:00",
                  "activity": {
                    "start": { "latLng": "40.7795°, -73.9556°" },
                    "end": { "latLng": "40.7347°, -73.9900°" },
                    "topCandidate": { "type": "IN_SUBWAY", "transitLine": "6 Train" }
                  }
                },
                {
                  "startTime": "2026-11-06T18:15:00.000-05:00",
                  "endTime": "2026-11-06T18:27:00.000-05:00",
                  "activity": {
                    "start": { "latLng": "40.8111°, -73.9523°" },
                    "end": { "latLng": "40.7683°, -73.9817°" },
                    "topCandidate": { "type": "IN_SUBWAY", "transitLine": "A Express" }
                  }
                }
              ]
            }
        """.trimIndent()
        val summary = timelineImporter.importTimelineJson(sampleTimelineJson, dao)
        if (!dao.lastSaveSucceeded) return 0
        _uiState.update {
            it.copy(
                statusMessage = "Google Maps Timeline: imported ${summary.ridesImported} new ride(s), skipped ${summary.duplicatesSkipped} duplicate(s)"
            )
        }
        return summary.ridesImported
    }

    fun importCustomTimelineJson(rawJson: String): Int {
        val timelineImporter = services?.timelineImporter ?: return 0
        val summary = timelineImporter.importTimelineJson(rawJson, dao)
        if (!dao.lastSaveSucceeded) return 0
        _uiState.update {
            it.copy(
                statusMessage = "Timeline JSON imported ${summary.ridesImported} ride(s) (${summary.multiLegRidesMerged} transfer(s) merged)"
            )
        }
        return summary.ridesImported
    }

    /** Returns the backup file contents (JSON) for all rides on this device. */
    suspend fun exportBackup(): String = backupManager.exportToJson()

    /** File name suggested in the save dialog, without extension: straphanger-backup-yyyy-MM-dd. */
    fun suggestedBackupName(): String {
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return "straphanger-backup-$today"
    }

    /** Records the outcome of an export in the status banner and returns the message. */
    fun reportBackupExported(fileName: String?): String =
        setStatus(
            buildString {
                append("Exported ${dao.getAllRides().size} ride(s)")
                if (!fileName.isNullOrBlank()) append(" to $fileName")
            }
        )

    fun reportBackupExportFailed(reason: String?): String =
        setStatus("Backup export failed: ${reason ?: "unknown error"}")

    private fun setStatus(message: String): String {
        _uiState.update { it.copy(statusMessage = message) }
        return message
    }

    /**
     * Restores rides from the bytes of a backup file. Rides already present are replaced, not
     * duplicated. Returns a failure when the bytes are not a Straphanger backup; a failure to
     * save the rides is thrown.
     */
    suspend fun importBackup(bytes: ByteArray): Result<Int> {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            return Result.failure(e)
        }
        return backupManager.importFromJson(text)
    }

    /** Records the outcome of a restore in the status banner and returns the message. */
    fun reportBackupRestored(result: Result<Int>): String =
        setStatus(
            result.fold(
                onSuccess = { n -> "Restored $n ride(s) from backup" },
                onFailure = { "Could not restore: the file is not a Straphanger backup" }
            )
        )

    /** Records a restore that failed while reading or saving rides, as opposed to a file that is not a backup. */
    fun reportBackupRestoreFailed(reason: String?): String =
        setStatus("Could not restore: the rides could not be saved (${reason ?: "unknown error"})")

    fun clearAllRides() {
        dao.clearAll()
        if (dao.lastSaveSucceeded) _uiState.update { it.copy(statusMessage = "Cleared all local rides") }
    }

    /** Adds three sample rides so the map and statistics have something to show. */
    fun loadDemoRides() {
        if (graphEngine == null) return
        seedStarterNycRides()
        if (dao.lastSaveSucceeded) _uiState.update { it.copy(statusMessage = "Loaded 3 sample rides") }
    }

    fun seedStarterNycRides() {
        val graphEngine = this.graphEngine ?: return
        // Spread the starter rides over the three days ending now, oldest first.
        val baseTime = clock.now().toEpochMilliseconds() - 172_800_000L - 24 * 60 * 60_000L
        // 1. Bedford Av (L) -> 14 St-Union Sq -> 86 St (4 Express)
        val ride1 = SubwayDao.buildCompleteRideRecord(
            rideId = "starter_1",
            startTimeEpochMs = baseTime,
            endTimeEpochMs = baseTime + 24 * 60_000L,
            source = RideSource.TIMELINE_IMPORT,
            resolved = graphEngine.resolveOriginToDestinationAuto(
                originStationId = StationIds.BEDFORD_AV_L,
                destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
                timestampEpochMs = baseTime
            ),
            graphEngine = graphEngine
        )
        // 2. A Express, 125 St -> 14 St (8 Av)
        val ride2 = SubwayDao.buildCompleteRideRecord(
            rideId = "starter_2",
            startTimeEpochMs = baseTime + 86_400_000L,
            endTimeEpochMs = baseTime + 86_400_000L + 19 * 60_000L,
            source = RideSource.GMAPS_NOTIFICATION,
            resolved = graphEngine.resolveTrip(
                legs = listOf(
                    TripLegRequest(StationIds.HARLEM_125_8AV, StationIds.FOURTEENTH_ST_8AV, preferredRouteId = "A", forceExpress = true)
                ),
                timestampEpochMs = baseTime + 86_400_000L
            ),
            graphEngine = graphEngine
        )
        // 3. 42 St Shuttle, Times Sq -> Grand Central
        val ride3 = SubwayDao.buildCompleteRideRecord(
            rideId = "starter_3",
            startTimeEpochMs = baseTime + 172_800_000L,
            endTimeEpochMs = baseTime + 172_800_000L + 4 * 60_000L,
            source = RideSource.TIMELINE_IMPORT,
            resolved = graphEngine.resolveTrip(
                legs = listOf(
                    TripLegRequest(StationIds.TIMES_SQ_SHUTTLE, StationIds.GRAND_CENTRAL_SHUTTLE, preferredRouteId = "GS")
                ),
                timestampEpochMs = baseTime + 172_800_000L
            ),
            graphEngine = graphEngine
        )
        dao.insertOrReplaceRides(listOf(ride1, ride2, ride3))
    }
}
