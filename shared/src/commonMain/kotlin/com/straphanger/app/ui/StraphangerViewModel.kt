package com.straphanger.app.ui

import com.straphanger.app.SharedRouteInbox
import com.straphanger.app.data.capture.DynamicGeofenceWindowManager
import com.straphanger.app.data.capture.GoogleMapsShareParser
import com.straphanger.app.data.capture.TimelineJsonImporter
import com.straphanger.app.data.gtfs.SubwayGraphEngine
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
    val stationGroupingMode: StationGroupingMode = StationGroupingMode.INDIVIDUAL_472,
    val stationVisitFilterMode: StationVisitFilterMode = StationVisitFilterMode.STOPPED_AT,
    val isLogRideSheetOpen: Boolean = false,
    val logOriginStationId: String = "L_BEDFORD",
    val logTransferStationId: String? = "L_14_USQ",
    val logDestinationStationId: String = "LEX_86",
    val logPreferredRouteId: String? = null,
    val logForceExpress: Boolean? = null,
    val passiveGeofenceEnabled: Boolean = true,
    val googleMapsNotificationEnabled: Boolean = true,
    val activeGeofenceCount: Int = 19,
    val statusMessage: String = "Rides are stored on this device",
    val loadError: String? = null
)

class StraphangerViewModel(
    backend: RidePersistenceBackend = InMemoryPersistenceBackend(),
    private val clock: Clock = Clock.System,
    seedInitialSampleRides: Boolean = false,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel(CoroutineScope(SupervisorJob() + dispatcher)) {

    val graphEngine = SubwayGraphEngine()
    val dao = SubwayDao(backend, clock)
    val statsCalculator = SubwayStatsCalculator(graphEngine)
    val timelineImporter = TimelineJsonImporter(graphEngine)
    val shareParser = GoogleMapsShareParser(graphEngine, clock = clock)
    val geofenceManager = DynamicGeofenceWindowManager(graphEngine)
    val backupManager = BackupExportManager(dao, clock)

    private val _uiState = MutableStateFlow(
        StraphangerUiState(
            rides = dao.getAllRides(),
            stats = statsCalculator.computeStats(dao.getAllRides()),
            loadError = dao.loadError.value,
            activeGeofenceCount = geofenceManager.computeRollingWindow(40.7347, -73.9899).activeGeofences.size
        )
    )
    val uiState: StateFlow<StraphangerUiState> = _uiState.asStateFlow()

    init {
        if (seedInitialSampleRides && dao.getAllRides().isEmpty()) {
            seedStarterNycRides()
        }
        viewModelScope.launch {
            dao.loadError.collect { error -> _uiState.update { it.copy(loadError = error) } }
        }
        viewModelScope.launch {
            dao.ridesFlow.collect { latestRides ->
                val latestStats = statsCalculator.computeStats(latestRides)
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
                        val parsed = shareParser.parseAndResolveSharedRoute(txt)
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

    fun simulateAmbiguousPassiveRide(): CompleteRideRecord {
        val ts = clock.now().toEpochMilliseconds()
        val resolved = graphEngine.resolveOriginToDestinationAuto(
            originStationId = "LEX_14_USQ",
            destinationStationId = "LEX_86",
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
        dao.confirmAmbiguousRide(rideId, routeId, isExpress, graphEngine)
        if (dao.lastSaveSucceeded) _uiState.update {
            it.copy(
                statusMessage = "Confirmed $routeId ${if (isExpress) "Express" else "Local"} for ride $rideId"
            )
        }
    }

    fun importSampleGoogleMapsTimeline(): Int {
        val sampleTimelineJson = """
            {
              "semanticSegments": [
                {
                  "startTime": "2026-11-05T08:30:00.000-05:00",
                  "endTime": "2026-11-05T08:48:00.000-05:00",
                  "activity": {
                    "start": { "latLng": "40.7596°, -73.8300°" },
                    "end": { "latLng": "40.7514°, -73.9760°" },
                    "topCandidate": { "type": "IN_SUBWAY", "transitLine": "7 Train" }
                  }
                },
                {
                  "startTime": "2026-11-06T18:15:00.000-05:00",
                  "endTime": "2026-11-06T18:34:00.000-05:00",
                  "activity": {
                    "start": { "latLng": "40.7682°, -73.9819°" },
                    "end": { "latLng": "40.6923°, -73.9873°" },
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
        seedStarterNycRides()
        if (dao.lastSaveSucceeded) _uiState.update { it.copy(statusMessage = "Loaded 3 sample rides") }
    }

    fun seedStarterNycRides() {
        // Spread the starter rides over the three days ending now, oldest first.
        val baseTime = clock.now().toEpochMilliseconds() - 172_800_000L - 24 * 60 * 60_000L
        // 1. L -> 4 Express multi-leg transfer (Brooklyn -> Manhattan)
        val ride1 = SubwayDao.buildCompleteRideRecord(
            rideId = "starter_1",
            startTimeEpochMs = baseTime,
            endTimeEpochMs = baseTime + 24 * 60_000L,
            source = RideSource.TIMELINE_IMPORT,
            resolved = graphEngine.resolveOriginToDestinationAuto(
                originStationId = "L_BEDFORD",
                destinationStationId = "LEX_86",
                timestampEpochMs = baseTime
            ),
            graphEngine = graphEngine
        )
        // 2. A Express (59 St-Columbus Circle -> Jay St-MetroTech)
        val ride2 = SubwayDao.buildCompleteRideRecord(
            rideId = "starter_2",
            startTimeEpochMs = baseTime + 86_400_000L,
            endTimeEpochMs = baseTime + 86_400_000L + 19 * 60_000L,
            source = RideSource.GMAPS_NOTIFICATION,
            resolved = graphEngine.resolveTrip(
                legs = listOf(TripLegRequest("IND8_59_COL", "IND8_JAY", preferredRouteId = "A", forceExpress = true)),
                timestampEpochMs = baseTime + 86_400_000L
            ),
            graphEngine = graphEngine
        )
        // 3. 7 Train (Times Sq-42 St -> 74 St-Broadway Jackson Hts Queens)
        val ride3 = SubwayDao.buildCompleteRideRecord(
            rideId = "starter_3",
            startTimeEpochMs = baseTime + 172_800_000L,
            endTimeEpochMs = baseTime + 172_800_000L + 21 * 60_000L,
            source = RideSource.TIMELINE_IMPORT,
            resolved = graphEngine.resolveTrip(
                legs = listOf(TripLegRequest("FL_42_TSQ", "FL_74_JACKSON", preferredRouteId = "7")),
                timestampEpochMs = baseTime + 172_800_000L
            ),
            graphEngine = graphEngine
        )
        dao.insertOrReplaceRides(listOf(ride1, ride2, ride3))
    }
}
