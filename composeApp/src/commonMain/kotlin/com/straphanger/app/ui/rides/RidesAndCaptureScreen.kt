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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet

@Composable
fun RidesHistoryAndLoggerScreen(
    uiState: StraphangerUiState,
    graphEngine: SubwayGraphEngine,
    onUpdateLogForm: (String, String?, String, String?, Boolean?) -> Unit,
    onSubmitQuickRide: () -> Unit,
    onSimulateAmbiguousRide: () -> Unit,
    onConfirmAmbiguousRide: (String, String, Boolean) -> Unit
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
        // 1. "Needs Review" Banner for Ambiguous Express vs. Local Passive Rides
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
                    text = "Needs Review (${pendingReviewRides.size} Passive Ride)",
                    color = MtaColors.AccentGold,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                pendingReviewRides.forEach { rec ->
                    val orig = graphEngine.getStation(rec.ride.originStationId)?.name ?: rec.ride.originStationId
                    val dest = graphEngine.getStation(rec.ride.destinationStationId)?.name ?: rec.ride.destinationStationId
                    Text(
                        text = "$orig → $dest (${rec.ride.totalDistanceMiles} mi) • Did you ride Express or Local?",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onConfirmAmbiguousRide(rec.ride.rideId, "4", true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00933C)),
                            modifier = Modifier.testTag("confirm_express_btn")
                        ) {
                            Text("Confirm 4 Express", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onConfirmAmbiguousRide(rec.ride.rideId, "6", false) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
                            modifier = Modifier.testTag("confirm_local_btn")
                        ) {
                            Text("Confirm 6 Local", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        onUpdateLogForm("L_BEDFORD", "L_14_USQ", "LEX_86", "4", true)
                    }
                )
                PresetChip(
                    label = "14 St → 86 St (6 Local)",
                    testTag = "preset_lex_6_local",
                    onClick = {
                        onUpdateLogForm("LEX_14_USQ", null, "LEX_86", "6", false)
                    }
                )
                PresetChip(
                    label = "Times Sq →Grand Central (S Shuttle)",
                    testTag = "preset_42_shuttle",
                    onClick = {
                        onUpdateLogForm("IRT7_42_TSQ", null, "LEX_42_GCT", "S", false)
                    }
                )
                PresetChip(
                    label = "Yankee Stadium → Atlantic-Barclays (D)",
                    testTag = "preset_bronx_bk_d",
                    onClick = {
                        onUpdateLogForm("IND6_161", null, "IND6_ATLANTIC", "D", true)
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
                    text = "Source: ${rec.ride.source.displayLabel} • $stoppedCount stops (${rec.legs.size} leg(s), $transferCount transfer(s))" +
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
    onImportCustomTimelineJson: (String) -> Unit,
    onTriggerCloudSync: () -> Unit,
    onGenerateBackupPreview: () -> Unit
) {
    var customTimelineText by remember { mutableStateOf("") }

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
            text = "Google Maps Capture & Firebase Sync",
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
                .testTag("sync_status_banner")
        ) {
            Text(
                text = uiState.syncStatusMessage,
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
                text = "Supports Android on-device Timeline.json, iOS Google Maps Timeline export, and Google Takeout Semantic Location History. Automatically merges 15-min transfers and deduplicates rides.",
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

            OutlinedTextField(
                value = customTimelineText,
                onValueChange = { customTimelineText = it },
                label = { Text("Paste Timeline.json payload (optional)", color = MtaColors.TextSecondary, fontSize = 11.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .testTag("custom_timeline_input")
            )
            if (customTimelineText.isNotBlank()) {
                Button(
                    onClick = { onImportCustomTimelineJson(customTimelineText) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00933C))
                ) {
                    Text("Parse & Import Custom Timeline JSON", fontSize = 12.sp)
                }
            }
        }

        // 2. Rolling Station Geofence Window & Google Maps Live Notification Capture
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
                text = "2. On-Phone Passive Geofences & Google Maps Live Listener",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "• Rolling Geofence Window: ${uiState.activeGeofenceCount}/20 active regions (18 nearest station complexes + 1 macro anchor — iOS CoreLocation & Android Play Services compliant)",
                color = MtaColors.AccentGreen,
                fontSize = 12.sp
            )
            Text(
                text = "• Android GoogleMapsTransitNotificationListener & ACTION_SEND Share Target: Active",
                color = MtaColors.TextSecondary,
                fontSize = 12.sp
            )
            Text(
                text = "• iOS straphanger://share URL Handler & CoreLocation Region Monitor: Active",
                color = MtaColors.TextSecondary,
                fontSize = 12.sp
            )
        }

        // 3. Firebase Cloud Firestore Sync (Spark Free Tier) & Local Backup
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
                text = "3. Firebase Cloud Firestore Sync (Free Spark Tier) & Backup",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onTriggerCloudSync,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6319)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sync_firestore_btn")
                ) {
                    Text("Sync to Firestore", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onGenerateBackupPreview,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_backup_btn")
                ) {
                    Text("Export JSON Backup", fontSize = 12.sp)
                }
            }

            if (!uiState.exportedBackupPreview.isNullOrBlank()) {
                Text(
                    text = uiState.exportedBackupPreview.take(280) + "...",
                    color = MtaColors.TextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("backup_json_preview")
                )
            }
        }
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
