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
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.ui.AppTab
import com.straphanger.app.ui.StraphangerViewModel
import com.straphanger.app.ui.map.SubwayMapScreen
import com.straphanger.app.ui.rides.DataSourcesAndSyncScreen
import com.straphanger.app.ui.rides.RidesHistoryAndLoggerScreen
import com.straphanger.app.ui.stats.StatsDashboardScreen
import com.straphanger.app.ui.theme.MtaColors

@Composable
fun App(
    viewModel: StraphangerViewModel = remember { StraphangerViewModel() }
) {
    val uiState by viewModel.uiState.collectAsState()
    val stats = uiState.stats

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

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (uiState.activeTab) {
                    AppTab.MAP -> SubwayMapScreen(
                        uiState = uiState,
                        network = viewModel.graphEngine.network,
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
                        graphEngine = viewModel.graphEngine,
                        onUpdateLogForm = { orig, trans, dest, route, exp ->
                            viewModel.updateLogForm(orig, trans, dest, route, exp)
                        },
                        onSubmitQuickRide = { viewModel.submitQuickRide() },
                        onSimulateAmbiguousRide = { viewModel.simulateAmbiguousPassiveRide() },
                        onConfirmAmbiguousRide = { id, r, exp ->
                            viewModel.confirmAmbiguousRide(id, r, exp)
                        }
                    )
                    AppTab.SOURCES -> DataSourcesAndSyncScreen(
                        uiState = uiState,
                        onImportSampleTimeline = { viewModel.importSampleGoogleMapsTimeline() },
                        onImportCustomTimelineJson = { viewModel.importCustomTimelineJson(it) },
                        onTriggerCloudSync = { viewModel.triggerCloudSync() },
                        onGenerateBackupPreview = { viewModel.generateBackupJsonPreview() }
                    )
                }
            }

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
