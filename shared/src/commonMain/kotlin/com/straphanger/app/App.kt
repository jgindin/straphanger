package com.straphanger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.straphanger.app.data.capture.GeofenceRegistrar
import com.straphanger.app.data.capture.InMemoryNotificationSessionStore
import com.straphanger.app.data.capture.InMemoryPendingGeofenceEventStore
import com.straphanger.app.data.capture.NotificationSessionStore
import com.straphanger.app.data.capture.PendingGeofenceEventStore
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.AppTab
import com.straphanger.app.ui.StraphangerViewModel
import com.straphanger.app.ui.about.AboutScreen
import com.straphanger.app.ui.map.SubwayMapScreen
import com.straphanger.app.ui.permissions.BackgroundLocationDisclosure
import com.straphanger.app.ui.permissions.NotificationAccessDisclosure
import com.straphanger.app.ui.rides.DataSourcesAndSyncScreen
import com.straphanger.app.ui.rides.RidesHistoryAndLoggerScreen
import com.straphanger.app.ui.stats.StatsDashboardScreen
import com.straphanger.app.ui.theme.MtaColors
import kotlinx.coroutines.launch

/**
 * @param geofenceRegistrar registers station geofences; null where passive capture is not available.
 * @param captureAccess the platform's current permission state; the platform refreshes it on resume.
 * @param pendingGeofenceEvents station entries recorded by the platform receiver.
 * @param notificationSessions Google Maps navigation sessions recorded by the notification listener.
 * @param onRequestPassiveCapture saves the user's choice to turn passive capture on and starts the
 *   platform's location permission flow.
 * @param onDisablePassiveCapture saves the user's choice to turn passive capture off.
 * @param onRequestNotificationAccess opens the platform's notification access settings.
 * @param appVersion the app's version name, shown on the About screen.
 * @param showDeveloperTools shows controls that fabricate rides; true only in debug and test builds.
 * @param licensesContent the open-source license list for the About screen; null where the platform has none.
 *
 * Both request callbacks run only after the user taps "Continue" on the matching disclosure.
 */
@OptIn(ExperimentalComposeUiApi::class) // ui-backhandler's BackHandler is still marked experimental in CMP 1.12
@Composable
fun App(
    backend: RidePersistenceBackend,
    geofenceRegistrar: GeofenceRegistrar? = null,
    pendingGeofenceEvents: PendingGeofenceEventStore = InMemoryPendingGeofenceEventStore(),
    notificationSessions: NotificationSessionStore = InMemoryNotificationSessionStore(),
    captureAccess: CaptureAccessState = CaptureAccessState(),
    onRequestPassiveCapture: () -> Unit = {},
    onDisablePassiveCapture: () -> Unit = {},
    onRequestNotificationAccess: () -> Unit = {},
    appVersion: String = "",
    licensesContent: (@Composable () -> Unit)? = null,
    showDeveloperTools: Boolean = false
) {
    val viewModel: StraphangerViewModel = viewModel {
        StraphangerViewModel(
            backend,
            geofenceRegistrar = geofenceRegistrar,
            pendingGeofenceEvents = pendingGeofenceEvents,
            notificationSessions = notificationSessions
        )
    }
    LaunchedEffect(captureAccess) { viewModel.updateCaptureAccess(captureAccess) }
    val uiState by viewModel.uiState.collectAsState()
    val stats = uiState.stats
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showBackgroundLocationDisclosure by rememberSaveable { mutableStateOf(false) }
    var showNotificationDisclosure by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    // System Back: a disclosure acts as "Not now", then About closes.
    BackHandler(enabled = showBackgroundLocationDisclosure) { showBackgroundLocationDisclosure = false }
    BackHandler(enabled = showNotificationDisclosure) { showNotificationDisclosure = false }
    BackHandler(enabled = showAbout && !showBackgroundLocationDisclosure && !showNotificationDisclosure) { showAbout = false }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = MtaColors.DarkBg,
            surface = MtaColors.CardBg,
            primary = Color(0xFF0039A6),
            onPrimary = Color.White,
            secondary = Color(0xFF2B3548),
            onSecondary = Color.White,
            onBackground = MtaColors.TextPrimary,
            onSurface = MtaColors.TextPrimary,
            onSurfaceVariant = MtaColors.TextSecondary
        )
    ) {
      CompositionLocalProvider(LocalContentColor provides MtaColors.TextPrimary) {
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
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
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
                } else if (showBackgroundLocationDisclosure) {
                    BackgroundLocationDisclosure(
                        onContinue = {
                            showBackgroundLocationDisclosure = false
                            onRequestPassiveCapture()
                        },
                        onNotNow = { showBackgroundLocationDisclosure = false }
                    )
                } else if (showNotificationDisclosure) {
                    NotificationAccessDisclosure(
                        onContinue = {
                            showNotificationDisclosure = false
                            onRequestNotificationAccess()
                        },
                        onNotNow = { showNotificationDisclosure = false }
                    )
                } else if (showAbout) {
                    AboutScreen(
                        appVersion = appVersion,
                        sourceDate = graphEngine.network.sourceDate,
                        onBack = { showAbout = false },
                        licensesContent = licensesContent
                    )
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
                        showDeveloperTools = showDeveloperTools,
                        onSimulateAmbiguousRide = { viewModel.simulateAmbiguousPassiveRide() },
                        onLoadDemoRides = { viewModel.loadDemoRides() },
                        onConfirmAmbiguousRide = { id, r, exp ->
                            viewModel.confirmAmbiguousRide(id, r, exp)
                        },
                        onKeepRideAsIs = viewModel::keepRideAsIs,
                        onDeleteRide = viewModel::deleteRide
                    )
                    AppTab.SOURCES -> DataSourcesAndSyncScreen(
                        uiState = uiState,
                        onImportSampleTimeline = { viewModel.importSampleGoogleMapsTimeline() },
                        onImportTimelineFile = { readBytes -> viewModel.importTimelineFileInBackground(readBytes) },
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
                        onRequestPassiveCapture = { showBackgroundLocationDisclosure = true },
                        onDisablePassiveCapture = onDisablePassiveCapture,
                        onRequestNotificationAccess = {
                            // Turning access off opens the same settings screen; the disclosure is for turning it on.
                            if (uiState.googleMapsNotificationEnabled) onRequestNotificationAccess()
                            else showNotificationDisclosure = true
                        },
                        onOpenAbout = { showAbout = true },
                        showDeveloperTools = showDeveloperTools
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
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
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
                            .clickable {
                                showAbout = false
                                showBackgroundLocationDisclosure = false
                                showNotificationDisclosure = false
                                viewModel.selectTab(tab)
                            }
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
