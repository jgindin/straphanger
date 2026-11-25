package com.straphanger.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import com.straphanger.app.domain.stats.SubwayAchievement
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.CardTitle
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.countOf
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.tabular
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_check

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
    HeroMetrics(uiState)
    StationCountingCard(uiState, onGroupingModeChange, onVisitFilterModeChange)
    LineCompletionCard(stats, onLineSelect)
    BoroughCard(stats)
    if (stats.topStations.isNotEmpty()) TopStationsCard(stats)
    if (stats.monthlyTrends.isNotEmpty()) MonthlyMilesCard(stats)
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
    CardTitle("Counting stations", style = MaterialTheme.typography.titleSmall)
    ModeChoice(
      modes = StationGroupingMode.entries,
      selected = uiState.stationGroupingMode,
      text = { "${uiState.stats.getDisplayedStationTotal(it)} ${it.label}" },
      onSelect = onGroupingModeChange,
      testTag = { "toggle_grouping_${it.name}" },
    )
    ModeChoice(
      modes = StationVisitFilterMode.entries,
      selected = uiState.stationVisitFilterMode,
      text = { it.label },
      onSelect = onVisitFilterModeChange,
      testTag = { "toggle_visit_${it.name}" },
    )
  }
}

/** One choice out of [modes], as a segmented button row. */
@Composable
private fun <T> ModeChoice(
  modes: List<T>,
  selected: T,
  text: (T) -> String,
  onSelect: (T) -> Unit,
  testTag: (T) -> String,
) {
  SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    modes.forEachIndexed { index, mode ->
      SegmentedButton(
        selected = selected == mode,
        onClick = { onSelect(mode) },
        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
        modifier = Modifier.testTag(testTag(mode)),
      ) {
        Text(text(mode), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/** Per-line completion bars with route bullets. */
@Composable
private fun LineCompletionCard(stats: SubwayStatsSnapshot, onLineSelect: (String) -> Unit) {
  SectionCard(spacing = 10.dp) {
    CardTitle("Lines")
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
    CardTitle("Boroughs")
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
private fun TopStationsCard(stats: SubwayStatsSnapshot) {
  SectionCard(spacing = 8.dp) {
    CardTitle("Top stations")
    stats.topStations.forEach { fav ->
      Text(
        text =
          "• ${fav.stationName} (${fav.borough.displayName}) — " +
            "${fav.visitCount} board/exit/transfer visits",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun MonthlyMilesCard(stats: SubwayStatsSnapshot) {
  SectionCard(spacing = 8.dp) {
    CardTitle("Monthly miles")
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
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CardTitle("Achievements")
      Text(
        text = "$unlocked of ${stats.achievements.size}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
      )
    }
    stats.achievements.forEach { AchievementRow(it) }
  }
}

@Composable
private fun AchievementRow(ach: SubwayAchievement) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(MaterialTheme.shapes.small)
        .background(
          if (ach.isUnlocked) MtaColors.SuccessContainer
          else MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        .padding(10.dp)
        .semantics(mergeDescendants = true) { if (ach.isUnlocked) stateDescription = "Unlocked" }
        .testTag("achievement_${ach.id}"),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (ach.isUnlocked) {
          // Decorative; the green card and the title carry the unlocked state.
          Icon(
            painterResource(Res.drawable.ic_check),
            contentDescription = null,
            tint = MtaColors.Success,
            modifier = Modifier.padding(end = Spacing.xs).size(18.dp),
          )
        }
        Text(
          text = ach.title,
          color = if (ach.isUnlocked) MtaColors.Success else MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
      }
      Text(
        text = ach.description,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = "${ach.currentValue.toInt()}/${ach.targetValue.toInt()}",
      color = if (ach.isUnlocked) MtaColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelLarge.tabular(),
    )
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
