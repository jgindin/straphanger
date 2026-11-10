package com.straphanger.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet

@Composable
fun StatsDashboardScreen(
    uiState: StraphangerUiState,
    onGroupingModeChange: (StationGroupingMode) -> Unit,
    onVisitFilterModeChange: (StationVisitFilterMode) -> Unit
) {
    val stats = uiState.stats
    val displayedStationsCount = stats.getDisplayedStationCount(
        groupingMode = uiState.stationGroupingMode,
        visitFilterMode = uiState.stationVisitFilterMode
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MtaColors.DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("stats_dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "NYC Subway System Analytics",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold
        )

        // Headline Hero Metrics Grid (2x2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatHeroCard(
                title = "Total Rides",
                value = "${stats.totalRides}",
                subtitle = "Since tracking started",
                accentColor = Color(0xFF00E5FF),
                testTag = "stat_card_total_rides",
                modifier = Modifier.weight(1f)
            )
            StatHeroCard(
                title = "Total Miles Ridden",
                value = "${stats.totalMilesRidden} mi",
                subtitle = "${stats.totalHoursUnderground} hrs underground",
                accentColor = Color(0xFFFF6319),
                testTag = "stat_card_total_miles",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatHeroCard(
                title = "Unique System Miles",
                value = "${stats.uniqueCorridorMilesRidden} mi",
                subtitle = "${stats.systemCompletionPercent}% of network covered",
                accentColor = MtaColors.AccentGreen,
                testTag = "stat_card_unique_miles",
                modifier = Modifier.weight(1f)
            )
            StatHeroCard(
                title = "Stations Visited",
                value = "$displayedStationsCount / ${uiState.stationGroupingMode.officialTotal}",
                subtitle = "${uiState.stationGroupingMode.label} • ${uiState.stationVisitFilterMode.label}",
                accentColor = MtaColors.AccentGold,
                testTag = "stat_card_stations_visited",
                modifier = Modifier.weight(1f)
            )
        }

        // Station Counting Rules Interactive Toggles (472 vs 424 & Stopped-At vs Boarded/Exited)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MtaColors.CardBg)
                .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Station Counting Mode",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StationGroupingMode.entries.forEach { mode ->
                    val active = uiState.stationGroupingMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Color(0xFF0039A6) else Color(0xFF1E2636))
                            .clickable { onGroupingModeChange(mode) }
                            .padding(vertical = 8.dp)
                            .testTag("toggle_grouping_${mode.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.label,
                            color = if (active) Color.White else MtaColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StationVisitFilterMode.entries.forEach { mode ->
                    val active = uiState.stationVisitFilterMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Color(0xFF00933C) else Color(0xFF1E2636))
                            .clickable { onVisitFilterModeChange(mode) }
                            .padding(vertical = 8.dp)
                            .testTag("toggle_visit_${mode.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.label,
                            color = if (active) Color.White else MtaColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Per-Line Completion Bars with Official MTA Bullets
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MtaColors.CardBg)
                .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Per-Line Track Completion",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            val sortedLines = stats.lineCompletionStats.sortedByDescending { it.completionPercent }
            sortedLines.take(12).forEach { lineStat ->
                val rId = lineStat.routeInfo.routeId
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MtaRouteBullet(routeId = rId, size = 22.dp, fontSize = 11.sp)
                            Text(
                                text = lineStat.routeInfo.name,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "${lineStat.completionPercent}% (${lineStat.uniqueCorridorMilesRidden}/${lineStat.totalCorridorMilesAvailable} mi • ${lineStat.legRideCount} legs)",
                            color = MtaColors.TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.testTag("line_stat_$rId")
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (lineStat.completionPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                        color = MtaColors.routeColor(rId),
                        trackColor = Color(0xFF232C3D),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }

        // Borough Breakdown
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
                text = "Borough Coverage & Mileage",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            stats.boroughStats.forEach { boro ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = boro.borough.displayName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${boro.stationsVisited}/${boro.totalStationsInGraph} stations • ${boro.milesRidden} mi (${boro.rideTouches} rides)",
                        color = MtaColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Top Favorites & Monthly Trends
        if (stats.topStations.isNotEmpty() || stats.monthlyTrends.isNotEmpty()) {
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
                    text = "Top Stations & Monthly Mileage Trends",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                stats.topStations.forEach { fav ->
                    Text(
                        text = "• ${fav.stationName} (${fav.borough.displayName}) — ${fav.visitCount} board/exit/transfer visits",
                        color = MtaColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                stats.monthlyTrends.forEach { trend ->
                    Text(
                        text = "Month ${trend.yearMonth}: ${trend.rideCount} rides • ${trend.totalMiles} total mi (+${trend.newUniqueMiles} new unique mi)",
                        color = MtaColors.AccentGold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Achievements & Milestones
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MtaColors.CardBg)
                .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Subway Achievements (${stats.achievements.count { it.isUnlocked }}/${stats.achievements.size} Unlocked)",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            stats.achievements.forEach { ach ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (ach.isUnlocked) Color(0xFF192822) else Color(0xFF1A202C))
                        .padding(10.dp)
                        .testTag("achievement_${ach.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${if (ach.isUnlocked) "✓ " else ""}${ach.title}",
                            color = if (ach.isUnlocked) MtaColors.AccentGreen else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = ach.description,
                            color = MtaColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${ach.currentValue.toInt()}/${ach.targetValue.toInt()}",
                        color = if (ach.isUnlocked) MtaColors.AccentGold else MtaColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun StatHeroCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MtaColors.CardBg)
            .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag(testTag)
    ) {
        Text(
            text = title,
            color = MtaColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = accentColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = MtaColors.TextSecondary,
            fontSize = 11.sp
        )
    }
}
