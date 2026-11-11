package com.straphanger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.straphanger.app.data.capture.GeofenceRegistrar
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.AppTab
import com.straphanger.app.ui.StraphangerViewModel
import com.straphanger.app.ui.map.SubwayMapScreen
import com.straphanger.app.ui.rides.DataSourcesAndSyncScreen
import com.straphanger.app.ui.rides.RidesHistoryAndLoggerScreen
import com.straphanger.app.ui.stats.StatsDashboardScreen
import com.straphanger.app.ui.theme.MtaColors
import kotlinx.coroutines.launch

/**
 * @param geofenceRegistrar registers station geofences; null where passive capture is not available.
 * @param captureAccess the platform's current permission state; the platform refreshes it on resume.
 * @param onRequestPassiveCapture starts the platform's location permission flow.
 * @param onRequestNotificationAccess opens the platform's notification access settings.
 */
@Composable
fun App(
    backend: RidePersistenceBackend,
    geofenceRegistrar: GeofenceRegistrar? = null,
    captureAccess: CaptureAccessState = CaptureAccessState(),
    onRequestPassiveCapture: () -> Unit = {},
    onRequestNotificationAccess: () -> Unit = {}
) {
    val viewModel: StraphangerViewModel = viewModel {
        StraphangerViewModel(backend, geofenceRegistrar = geofenceRegistrar)
    }
    LaunchedEffect(captureAccess) { viewModel.updateCaptureAccess(captureAccess) }
    val uiState by viewModel.uiState.collectAsState()
    val stats = uiState.stats
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = MtaColors.DarkBg,
            surface = MtaColors.CardBg,
            primary = Color(0xFF0039A6)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MtaColors.DarkBg)
                .testTag("straphanger_root")
        ) {
            // Top NYC Subway Header Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF10151F))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "STRAPHANGER",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.testTag("header_title")
                    )
                    Text(
                        text = "NYC Subway Coverage & Ride Tracker",
                        color = MtaColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeaderQuickMetric(
                        label = "Rides",
                        value = "${stats.totalRides}",
                        testTag = "header_total_rides"
                    )
                    HeaderQuickMetric(
                        label = "Miles",
                        value = "${stats.totalMilesRidden}",
                        testTag = "header_total_miles"
                    )
                    HeaderQuickMetric(
                        label = "Unique",
                        value = "${stats.uniqueCorridorMilesRidden} mi",
                        testTag = "header_unique_miles"
                    )
                }
            }

            val storageError = uiState.loadError
            if (storageError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A1416))
                        .border(1.dp, Color(0xFFD64545))
                        .padding(12.dp)
                        .testTag("storage_error_banner")
                ) {
                    Text(text = storageError, color = Color.White, fontSize = 12.sp)
                }
            }

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val graphEngine = viewModel.graphEngine
                if (!uiState.isNetworkLoaded || graphEngine == null) {
                    Box(
                        modifier = Modifier.fillMaxSize().testTag("network_loading"),
                        contentAlignment = Alignment.Center
                    ) {
                        val loadError = uiState.networkLoadError
                        Text(
                            text = loadError ?: "Loading subway network",
                            color = if (loadError != null) Color.White else MtaColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .padding(16.dp)
                                .testTag(if (loadError != null) "network_load_error" else "network_loading_text")
                        )
                    }
                } else when (uiState.activeTab) {
                    AppTab.MAP -> SubwayMapScreen(
                        uiState = uiState,
                        network = graphEngine.network,
                        onColorModeChange = viewModel::setMapColorMode,
                        onRouteFilterSelect = viewModel::setRouteFilter,
                        onStationSelect = viewModel::selectStationOnMap,
                        onStartRideFromStation = { stId ->
                            viewModel.updateLogForm(originId = stId)
                            viewModel.selectTab(AppTab.RIDES)
                        },
                        onEndRideAtStation = { stId ->
                            viewModel.updateLogForm(destinationId = stId)
                            viewModel.selectTab(AppTab.RIDES)
                        }
                    )
                    AppTab.STATS -> StatsDashboardScreen(
                        uiState = uiState,
                        onGroupingModeChange = viewModel::setStationGroupingMode,
                        onVisitFilterModeChange = viewModel::setStationVisitFilterMode
                    )
                    AppTab.RIDES -> RidesHistoryAndLoggerScreen(
                        uiState = uiState,
                        graphEngine = graphEngine,
                        onUpdateLogForm = { orig, trans, dest, route, exp ->
                            viewModel.updateLogForm(orig, trans, dest, route, exp)
                        },
                        onSubmitQuickRide = { viewModel.submitQuickRide() },
                        onSimulateAmbiguousRide = { viewModel.simulateAmbiguousPassiveRide() },
                        onLoadDemoRides = { viewModel.loadDemoRides() },
                        onConfirmAmbiguousRide = { id, r, exp ->
                            viewModel.confirmAmbiguousRide(id, r, exp)
                        }
                    )
                    AppTab.SOURCES -> DataSourcesAndSyncScreen(
                        uiState = uiState,
                        onImportSampleTimeline = { viewModel.importSampleGoogleMapsTimeline() },
                        onImportTimelineFile = { viewModel.importTimelineFile(it) },
                        suggestedBackupName = viewModel.suggestedBackupName(),
                        onExportBackup = { viewModel.exportBackup() },
                        onBackupExported = viewModel::reportBackupExported,
                        onBackupExportFailed = viewModel::reportBackupExportFailed,
                        onBackupRestoreFailed = viewModel::reportBackupRestoreFailed,
                        onImportBackup = { viewModel.importBackup(it) },
                        onBackupRestored = viewModel::reportBackupRestored,
                        onBackupMessage = { message ->
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        onRequestPassiveCapture = {
                            viewModel.enablePassiveCapture()
                            onRequestPassiveCapture()
                        },
                        onDisablePassiveCapture = viewModel::disablePassiveCapture,
                        onRequestNotificationAccess = onRequestNotificationAccess
                    )
                }
            }

            SnackbarHost(snackbarHostState, modifier = Modifier.testTag("backup_snackbar"))

            // Bottom Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF10151F))
                    .border(1.dp, MtaColors.CardBorder)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppTab.entries.forEach { tab ->
                    val selected = uiState.activeTab == tab
                    val badgeCount = if (tab == AppTab.RIDES) stats.pendingReviewCount else 0
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0xFF0039A6) else Color.Transparent)
                            .clickable { viewModel.selectTab(tab) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("nav_tab_${tab.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        val suffix = if (badgeCount > 0) " ($badgeCount)" else ""
                        Text(
                            text = "${tab.label}$suffix",
                            color = if (selected) Color.White else MtaColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderQuickMetric(
    label: String,
    value: String,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.testTag(testTag)
    ) {
        Text(
            text = value,
            color = MtaColors.AccentGold,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = label,
            color = MtaColors.TextSecondary,
            fontSize = 10.sp
        )
    }
}
