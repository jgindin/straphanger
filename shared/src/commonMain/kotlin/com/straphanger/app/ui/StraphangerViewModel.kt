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
import com.straphanger.app.data.sync.FirestoreKmpSyncClient
import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import com.straphanger.app.domain.stats.SubwayStatsCalculator
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
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
    SOURCES("Google Maps & Sync")
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
    val syncStatusMessage: String = "Local-first SQLite active • Offline-ready underground",
    val exportedBackupPreview: String? = null
)

class StraphangerViewModel(
    backend: RidePersistenceBackend = InMemoryPersistenceBackend(),
    seedInitialSampleRides: Boolean = true,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val graphEngine = SubwayGraphEngine()
    val dao = SubwayDao(backend)
    val statsCalculator = SubwayStatsCalculator(graphEngine)
    val timelineImporter = TimelineJsonImporter(graphEngine)
    val shareParser = GoogleMapsShareParser(graphEngine)
    val geofenceManager = DynamicGeofenceWindowManager(graphEngine)
    val firestoreSync = FirestoreKmpSyncClient(dao)
    val backupManager = BackupExportManager(dao)

    private val _uiState = MutableStateFlow(
        StraphangerUiState(
            rides = dao.getAllRides(),
            stats = statsCalculator.computeStats(dao.getAllRides()),
            activeGeofenceCount = geofenceManager.computeRollingWindow(40.7347, -73.9899).activeGeofences.size
        )
    )
    val uiState: StateFlow<StraphangerUiState> = _uiState.asStateFlow()

    init {
        if (seedInitialSampleRides && dao.getAllRides().isEmpty()) {
            seedStarterNycRides()
        }
        scope.launch {
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
        scope.launch {
            SharedRouteInbox.pendingSharedTexts.collect { pending ->
                if (pending.isNotEmpty()) {
                    val items = SharedRouteInbox.consumeAll()
                    for (txt in items) {
                        val parsed = shareParser.parseAndResolveSharedRoute(txt)
                        if (parsed != null) {
                            dao.insertOrReplaceRide(parsed)
                            _uiState.update {
                                it.copy(syncStatusMessage = "Auto-captured ride from Google Maps: ${parsed.ride.originStationId} → ${parsed.ride.destinationStationId}")
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
        timestampEpochMs: Long = 1794250800000L + (dao.getAllRides().size * 3600_000L)
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
        _uiState.update {
            it.copy(
                isLogRideSheetOpen = false,
                syncStatusMessage = "Logged ride (${record.ride.totalDistanceMiles} mi across ${record.legs.size} leg(s))"
            )
        }
        return record
    }

    fun simulateAmbiguousPassiveRide(): CompleteRideRecord {
        val ts = 1794258000000L + (dao.getAllRides().size * 600_000L)
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
        _uiState.update {
            it.copy(
                syncStatusMessage = "Passively detected 14 St–Union Sq → 86 St • Tap to confirm 4 Express vs 6 Local"
            )
        }
        return record
    }

    fun confirmAmbiguousRide(rideId: String, routeId: String, isExpress: Boolean) {
        dao.confirmAmbiguousRide(rideId, routeId, isExpress, graphEngine)
        _uiState.update {
            it.copy(
                syncStatusMessage = "Confirmed $routeId ${if (isExpress) "Express" else "Local"} for ride $rideId"
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
        _uiState.update {
            it.copy(
                syncStatusMessage = "Google Maps Timeline: imported ${summary.ridesImported} new ride(s), skipped ${summary.duplicatesSkipped} duplicate(s)"
            )
        }
        return summary.ridesImported
    }

    fun importCustomTimelineJson(rawJson: String): Int {
        val summary = timelineImporter.importTimelineJson(rawJson, dao)
        _uiState.update {
            it.copy(
                syncStatusMessage = "Timeline JSON imported ${summary.ridesImported} ride(s) (${summary.multiLegRidesMerged} transfer(s) merged)"
            )
        }
        return summary.ridesImported
    }

    fun triggerCloudSync() {
        scope.launch {
            val res = firestoreSync.syncPendingRides()
            _uiState.update { it.copy(syncStatusMessage = res.statusMessage) }
        }
    }

    fun generateBackupJsonPreview() {
        val json = backupManager.exportToJson()
        _uiState.update {
            it.copy(
                exportedBackupPreview = json,
                syncStatusMessage = "Generated JSON backup (${dao.getAllRides().size} rides)"
            )
        }
    }

    fun clearAllRides() {
        dao.clearAll()
        _uiState.update { it.copy(syncStatusMessage = "Cleared all local rides") }
    }

    fun seedStarterNycRides() {
        val baseTime = 1793800000000L
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
