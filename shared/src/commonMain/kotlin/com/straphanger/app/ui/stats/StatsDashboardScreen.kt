package com.straphanger.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.CardTitle
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.countOf
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.tabular

@Composable
fun StatsDashboardScreen(
  uiState: StraphangerUiState,
  onGroupingModeChange: (StationGroupingMode) -> Unit,
  onVisitFilterModeChange: (StationVisitFilterMode) -> Unit,
  onLineSelect: (String) -> Unit = {},
) {
  val stats = uiState.stats
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState())
        .padding(Spacing.lg)
        .testTag("stats_dashboard_screen"),
    verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    Text(
      text = "NYC Subway System Analytics",
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
    )
    HeroMetrics(uiState)
    StationCountingCard(uiState, onGroupingModeChange, onVisitFilterModeChange)
    LineCompletionCard(stats, onLineSelect)
    BoroughCard(stats)
    if (stats.topStations.isNotEmpty() || stats.monthlyTrends.isNotEmpty()) {
      TopStationsAndTrendsCard(stats)
    }
    AchievementsCard(stats)
  }
}

/** Headline metrics in a 2x2 grid. */
@Composable
private fun HeroMetrics(uiState: StraphangerUiState) {
  val stats = uiState.stats
  val displayedStationsCount =
    stats.getDisplayedStationCount(
      groupingMode = uiState.stationGroupingMode,
      visitFilterMode = uiState.stationVisitFilterMode,
    )
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    StatHeroCard(
      title = "Total Rides",
      value = "${stats.totalRides}",
      subtitle = "Since tracking started",
      testTag = "stat_card_total_rides",
      modifier = Modifier.weight(1f),
    )
    StatHeroCard(
      title = "Total Miles Ridden",
      value = "${stats.totalMilesRidden} mi",
      subtitle = "${stats.totalHoursUnderground} hrs underground",
      testTag = "stat_card_total_miles",
      modifier = Modifier.weight(1f),
    )
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    StatHeroCard(
      title = "Unique System Miles",
      value = "${stats.uniqueCorridorMilesRidden} mi",
      subtitle = "${stats.systemCompletionPercent}% of network covered",
      testTag = "stat_card_unique_miles",
      modifier = Modifier.weight(1f),
    )
    StatHeroCard(
      title = "Stations Visited",
      value =
        "$displayedStationsCount / ${stats.getDisplayedStationTotal(uiState.stationGroupingMode)}",
      subtitle = "${uiState.stationGroupingMode.label} • ${uiState.stationVisitFilterMode.label}",
      testTag = "stat_card_stations_visited",
      modifier = Modifier.weight(1f),
    )
  }
}

/** Station counting toggles: stations or complexes, stopped at or boarded/exited. */
@Composable
private fun StationCountingCard(
  uiState: StraphangerUiState,
  onGroupingModeChange: (StationGroupingMode) -> Unit,
  onVisitFilterModeChange: (StationVisitFilterMode) -> Unit,
) {
  SectionCard(spacing = 10.dp) {
    CardTitle("Station Counting Mode", style = MaterialTheme.typography.titleSmall)
    Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StationGroupingMode.entries.forEach { mode ->
        ModeToggle(
          text = "${uiState.stats.getDisplayedStationTotal(mode)} ${mode.label}",
          active = uiState.stationGroupingMode == mode,
          onClick = { onGroupingModeChange(mode) },
          testTag = "toggle_grouping_${mode.name}",
        )
      }
    }
    Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StationVisitFilterMode.entries.forEach { mode ->
        ModeToggle(
          text = mode.label,
          active = uiState.stationVisitFilterMode == mode,
          onClick = { onVisitFilterModeChange(mode) },
          testTag = "toggle_visit_${mode.name}",
        )
      }
    }
  }
}

@Composable
private fun RowScope.ModeToggle(
  text: String,
  active: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  Box(
    modifier =
      Modifier.weight(1f)
        .minimumInteractiveComponentSize()
        .clip(MaterialTheme.shapes.small)
        .background(
          if (active) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = Spacing.sm)
        .testTag(testTag),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color =
        if (active) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
    )
  }
}

/** Per-line completion bars with route bullets. */
@Composable
private fun LineCompletionCard(stats: SubwayStatsSnapshot, onLineSelect: (String) -> Unit) {
  SectionCard(spacing = 10.dp) {
    CardTitle("Per-Line Track Completion")
    val sortedLines = stats.lineCompletionStats.sortedByDescending { it.completionPercent }
    sortedLines.take(12).forEach { lineStat ->
      val rId = lineStat.routeInfo.routeId
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onLineSelect(rId) }
            .testTag("line_row_$rId"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            MtaRouteBullet(routeId = rId, size = 22.dp, fontSize = 11.sp)
            Text(
              text = lineStat.routeInfo.name,
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.titleSmall,
            )
          }
          Text(
            text =
              "${lineStat.completionPercent}% (${lineStat.uniqueCorridorMilesRidden}/" +
                "${lineStat.totalCorridorMilesAvailable} mi • " +
                "${countOf(lineStat.legRideCount, "leg")})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.tabular(),
            modifier = Modifier.testTag("line_stat_$rId"),
          )
        }
        LinearProgressIndicator(
          progress = { (lineStat.completionPercent / 100.0).toFloat().coerceIn(0f, 1f) },
          color = MtaColors.routeColor(rId),
          trackColor = MaterialTheme.colorScheme.outlineVariant,
          modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
        )
      }
    }
  }
}

@Composable
private fun BoroughCard(stats: SubwayStatsSnapshot) {
  SectionCard(spacing = 8.dp) {
    CardTitle("Borough Coverage & Mileage")
    stats.boroughStats.forEach { boro ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = boro.borough.displayName,
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        Text(
          text =
            "${boro.stationsVisited}/${boro.totalStationsInGraph} stations • " +
              "${boro.milesRidden} mi (${countOf(boro.rideTouches, "ride")})",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall.tabular(),
        )
      }
    }
  }
}

@Composable
private fun TopStationsAndTrendsCard(stats: SubwayStatsSnapshot) {
  SectionCard(spacing = 8.dp) {
    CardTitle("Top Stations & Monthly Mileage Trends")
    stats.topStations.forEach { fav ->
      Text(
        text =
          "• ${fav.stationName} (${fav.borough.displayName}) — " +
            "${fav.visitCount} board/exit/transfer visits",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    stats.monthlyTrends.forEach { trend ->
      Text(
        text =
          "Month ${trend.yearMonth}: ${countOf(trend.rideCount, "ride")} • " +
            "${trend.totalMiles} total mi (+${trend.newUniqueMiles} new unique mi)",
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium.tabular(),
      )
    }
  }
}

@Composable
private fun AchievementsCard(stats: SubwayStatsSnapshot) {
  SectionCard(spacing = 10.dp) {
    val unlocked = stats.achievements.count { it.isUnlocked }
    CardTitle("Subway Achievements ($unlocked/${stats.achievements.size} Unlocked)")
    stats.achievements.forEach { ach ->
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
              if (ach.isUnlocked) MtaColors.SuccessContainer
              else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .padding(10.dp)
            .testTag("achievement_${ach.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "${if (ach.isUnlocked) "✓ " else ""}${ach.title}",
            color = if (ach.isUnlocked) MtaColors.Success else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
          )
          Text(
            text = ach.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "${ach.currentValue.toInt()}/${ach.targetValue.toInt()}",
          color =
            if (ach.isUnlocked) MtaColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelLarge.tabular(),
        )
      }
    }
  }
}

@Composable
private fun StatHeroCard(
  title: String,
  value: String,
  subtitle: String,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        .padding(Spacing.md)
        .testTag(testTag),
  ) {
    Text(
      text = title,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = value,
      color = MaterialTheme.colorScheme.primary,
      style =
        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold).tabular(),
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = subtitle,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}
