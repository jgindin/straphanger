package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.StopRole
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.ui.StationIds
import com.straphanger.app.ui.countOf
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RidesHistoryAndLoggerScreen(
    uiState: StraphangerUiState,
    graphEngine: SubwayGraphEngine,
    onUpdateLogForm: (String, String?, String, String?, Boolean?) -> Unit,
    onSubmitQuickRide: () -> Unit,
    onSimulateAmbiguousRide: () -> Unit,
    showDeveloperTools: Boolean = false,
    onLoadDemoRides: () -> Unit,
    onConfirmAmbiguousRide: (String, String, Boolean) -> Unit,
    onKeepRideAsIs: (String) -> Unit = {}
) {
    val pendingReviewRides = uiState.rides.filter { it.ride.needsReview }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MtaColors.DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("rides_history_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Rides that need review: express or local, or a station name that matched several stations
        if (pendingReviewRides.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF281F12))
                    .border(1.dp, MtaColors.AccentGold, RoundedCornerShape(14.dp))
                    .padding(14.dp)
                    .testTag("needs_review_banner"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Needs review: ${countOf(pendingReviewRides.size, "ride")}",
                    color = MtaColors.AccentGold,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                pendingReviewRides.forEach { rec ->
                    val rideId = rec.ride.rideId
                    val orig = graphEngine.getStation(rec.ride.originStationId)?.name ?: rec.ride.originStationId
                    val dest = graphEngine.getStation(rec.ride.destinationStationId)?.name ?: rec.ride.destinationStationId
                    val review = reviewFor(rec.ride)
                    Text(
                        text = "$orig → $dest (${rec.ride.totalDistanceMiles} mi). ${review.prompt}",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        review.actions.forEach { action ->
                            when (action) {
                                is ReviewAction.ConfirmCandidate -> Button(
                                    onClick = { onConfirmAmbiguousRide(rideId, action.routeId, action.isExpress) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (action.index == 0) MtaColors.ActionGreen else Color(0xFF2B3548)
                                    ),
                                    modifier = Modifier.testTag("confirm_candidate_${rideId}_${action.index}")
                                ) {
                                    Text(action.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                ReviewAction.KeepAsIs -> Button(
                                    onClick = { onKeepRideAsIs(rideId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
                                    modifier = Modifier.testTag("keep_as_is_$rideId")
                                ) {
                                    Text("Keep as is", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Quick Trip Logger Card (Supports Origin, Optional Transfer, Destination, Express/Local)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MtaColors.CardBg)
                .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp)
                .testTag("quick_trip_logger_card"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Quick Trip Logger (Single or Multi-Leg Transfer)",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            val origName = graphEngine.getStation(uiState.logOriginStationId)?.name ?: uiState.logOriginStationId
            val transName = uiState.logTransferStationId?.let { graphEngine.getStation(it)?.name } ?: "None (Direct / Auto)"
            val destName = graphEngine.getStation(uiState.logDestinationStationId)?.name ?: uiState.logDestinationStationId

            Text(
                text = "Origin: $origName  →  Transfer: $transName  →  Destination: $destName",
                color = MtaColors.AccentGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            // Quick Preset Route Buttons for fast 1-tap logging & testing
            Text(
                text = "Popular Trip Presets (or tap any station on the Map tab):",
                color = MtaColors.TextSecondary,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetChip(
                    label = "Bedford (L) → 14 St → 86 St (4X)",
                    testTag = "preset_transfer_l_4",
                    onClick = {
                        onUpdateLogForm(StationIds.BEDFORD_AV_L, StationIds.UNION_SQ_L, StationIds.EIGHTY_SIXTH_LEX, "4", true)
                    }
                )
                PresetChip(
                    label = "14 St → 86 St (6 Local)",
                    testTag = "preset_lex_6_local",
                    onClick = {
                        onUpdateLogForm(StationIds.UNION_SQ_LEX, null, StationIds.EIGHTY_SIXTH_LEX, "6", false)
                    }
                )
                PresetChip(
                    label = "Times Sq →Grand Central (S Shuttle)",
                    testTag = "preset_42_shuttle",
                    onClick = {
                        onUpdateLogForm(StationIds.TIMES_SQ_SHUTTLE, null, StationIds.GRAND_CENTRAL_SHUTTLE, "GS", false)
                    }
                )
                PresetChip(
                    label = "Yankee Stadium → Atlantic-Barclays (D)",
                    testTag = "preset_bronx_bk_d",
                    onClick = {
                        onUpdateLogForm(StationIds.YANKEE_STADIUM_D, null, StationIds.ATLANTIC_AV_D, "D", true)
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSubmitQuickRide,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0039A6)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("submit_log_ride_btn")
                ) {
                    Text("Log Subway Ride", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                if (showDeveloperTools) {
                    Button(
                        onClick = onSimulateAmbiguousRide,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF243044)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("simulate_passive_ride_btn")
                    ) {
                        Text("Simulate Passive GPS Ride", fontSize = 12.sp)
                    }
                }
            }
        }

        Button(
            onClick = onLoadDemoRides,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF243044)),
            modifier = Modifier.testTag("load_demo_rides_btn")
        ) {
            Text("Load sample rides", fontSize = 12.sp)
        }

        // 3. Ride History List
        Text(
            text = "Recorded Subway Rides (${uiState.rides.size})",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        uiState.rides.forEach { rec ->
            val origName = graphEngine.getStation(rec.ride.originStationId)?.name ?: rec.ride.originStationId
            val destName = graphEngine.getStation(rec.ride.destinationStationId)?.name ?: rec.ride.destinationStationId
            val stoppedCount = rec.stationStops.size
            val transferCount = rec.stationStops.count { it.stopRole == StopRole.TRANSFERRED }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MtaColors.CardBg)
                    .border(
                        width = 1.dp,
                        color = if (rec.ride.needsReview) MtaColors.AccentGold else MtaColors.CardBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp)
                    .testTag("ride_item_${rec.ride.rideId}"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rec.legs.forEach { leg ->
                            MtaRouteBullet(routeId = leg.routeId, size = 24.dp, fontSize = 11.sp)
                            Text(
                                text = if (leg.isExpress) "Express" else "Local",
                                color = MtaColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        text = "${rec.ride.totalDistanceMiles} mi • ${rec.ride.durationMinutes} min",
                        color = MtaColors.AccentGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "$origName → $destName",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Source: ${rec.ride.source.displayLabel} • $stoppedCount stops (${countOf(rec.legs.size, "leg")}, ${countOf(transferCount, "transfer")})" +
                        if (rec.ride.needsReview) " • NEEDS REVIEW" else "",
                    color = if (rec.ride.needsReview) MtaColors.AccentGold else MtaColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun DataSourcesAndSyncScreen(
    uiState: StraphangerUiState,
    onImportSampleTimeline: () -> Unit,
    /** Starts a Timeline import that reads the chosen file; it runs in the view model, not this screen. */
    onImportTimelineFile: (readBytes: suspend () -> ByteArray) -> Unit,
    suggestedBackupName: String,
    onExportBackup: suspend () -> String,
    onBackupExported: (fileName: String?) -> String,
    onBackupExportFailed: (reason: String?) -> String,
    onBackupRestoreFailed: (reason: String?) -> String,
    onImportBackup: suspend (ByteArray) -> Result<Int>,
    onBackupRestored: (Result<Int>) -> String,
    onBackupMessage: (String) -> Unit,
    onRequestPassiveCapture: () -> Unit = {},
    onDisablePassiveCapture: () -> Unit = {},
    onRequestNotificationAccess: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val timelineLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(listOf("json")),
        onError = { failure -> onBackupMessage("Could not open the file picker: ${failure.message ?: "unknown error"}") }
    ) { file ->
        if (file != null) onImportTimelineFile { file.readBytes() }
    }
    val restoreLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(listOf("json")),
        onError = { failure -> onBackupMessage("Could not open the file picker: ${failure.message ?: "unknown error"}") }
    ) { file ->
        if (file != null) {
            scope.launch {
                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onBackupMessage("Could not read the file: ${e.message ?: "unknown error"}")
                    return@launch
                }
                val message = try {
                    onBackupRestored(onImportBackup(bytes))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onBackupRestoreFailed(e.message)
                }
                onBackupMessage(message)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MtaColors.DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("sources_sync_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Import & Backup",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold
        )

        // Status Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF162234))
                .border(1.dp, Color(0xFF284268), RoundedCornerShape(10.dp))
                .padding(12.dp)
                .testTag("status_banner")
        ) {
            Text(
                text = uiState.statusMessage,
                color = MtaColors.AccentGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 1. Google Maps Timeline.json Importer (Android / iOS / Takeout)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MtaColors.CardBg)
                .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "1. On-Device Google Maps Timeline.json Import",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Supports Android on-device Timeline.json, iOS Google Maps Timeline export, and Google Takeout Semantic Location History. Transfers made within 15 minutes are merged into one ride, and rides already stored are skipped.",
                color = MtaColors.TextSecondary,
                fontSize = 12.sp
            )
            Button(
                onClick = onImportSampleTimeline,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0039A6)),
                modifier = Modifier.testTag("import_sample_timeline_btn")
            ) {
                Text("Import Google Maps Timeline.json Sample", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { timelineLauncher.launch() },
                enabled = !uiState.timelineImportInProgress,
                colors = ButtonDefaults.buttonColors(containerColor = MtaColors.ActionGreen),
                modifier = Modifier.testTag("pick_timeline_file_btn")
            ) {
                Text("Choose Timeline file…", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (uiState.timelineImportInProgress) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("timeline_import_progress")
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Importing…", color = MtaColors.TextSecondary, fontSize = 12.sp)
                }
            }
            uiState.lastImportSummary?.let { summary ->
                Text(
                    text = "Last import: ${countOf(summary.rawSegmentsFound, "subway segment")} found, " +
                        "${countOf(summary.ridesImported, "ride")} imported, " +
                        "${countOf(summary.multiLegRidesMerged, "transfer")} merged, " +
                        "${countOf(summary.duplicatesSkipped, "duplicate")} skipped, " +
                        "${summary.unmatchedSegmentsSkipped} not matched to stations",
                    color = MtaColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("timeline_import_summary")
                )
            }
        }

        // 2. Passive station geofences and the Google Maps notification listener
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MtaColors.CardBg)
                .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "2. Passive Capture",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            CaptureToggleRow(
                title = "Station geofences",
                detail = when {
                    uiState.passiveGeofenceEnabled ->
                        "${uiState.activeGeofenceCount} regions registered (nearest station complexes and 1 window anchor). Rides are recorded when you enter a second station at subway speed."
                    uiState.passiveCaptureError != null -> uiState.passiveCaptureError
                    else -> "Off"
                },
                checked = uiState.passiveGeofenceEnabled,
                onCheckedChange = { on -> if (on) onRequestPassiveCapture() else onDisablePassiveCapture() },
                testTag = "passive_capture_toggle"
            )
            CaptureToggleRow(
                title = "Google Maps notifications",
                detail = if (uiState.googleMapsNotificationEnabled) {
                    "Notification access granted. Rides are read from Google Maps transit navigation."
                } else {
                    "Notification access is off. Turning this on opens system settings."
                },
                checked = uiState.googleMapsNotificationEnabled,
                onCheckedChange = { onRequestNotificationAccess() },
                testTag = "notification_access_toggle"
            )
        }

        // 3. Backup and restore through files
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MtaColors.CardBg)
                .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "3. Backup & Restore",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Export saves all rides to a JSON file you choose. Restore reads a file exported by this app; rides already on this device are replaced, not duplicated.",
                color = MtaColors.TextSecondary,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val file = FileKit.openFileSaver(
                                    suggestedName = suggestedBackupName,
                                    defaultExtension = "json"
                                )
                                if (file != null) {
                                    file.writeString(onExportBackup())
                                    onBackupMessage(onBackupExported(file.name))
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                onBackupMessage(onBackupExportFailed(e.message))
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_backup_btn")
                ) {
                    Text("Export backup…", fontSize = 12.sp)
                }
                Button(
                    onClick = { restoreLauncher.launch() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_backup_btn")
                ) {
                    Text("Restore from backup…", fontSize = 12.sp)
                }
            }
        }

        Button(
            onClick = onOpenAbout,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
            modifier = Modifier.testTag("open_about_btn")
        ) {
            Text("About, data sources and licenses", fontSize = 12.sp)
        }
    }
}

@Composable
private fun CaptureToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = detail,
                color = MtaColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.testTag("${testTag}_detail")
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun PresetChip(
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1F293A))
            .border(1.dp, Color(0xFF33435E), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
