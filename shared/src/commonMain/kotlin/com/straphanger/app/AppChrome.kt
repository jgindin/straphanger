package com.straphanger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.AppTab
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.tabular

@Composable
internal fun AppHeader(stats: SubwayStatsSnapshot) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .windowInsetsPadding(
          WindowInsets.statusBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        )
        .padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = Spacing.md)) {
      Text(
        text = "STRAPHANGER",
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("header_title"),
      )
      Text(
        text = "NYC Subway Coverage & Ride Tracker",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      HeaderQuickMetric(
        label = "Rides",
        value = "${stats.totalRides}",
        testTag = "header_total_rides",
      )
      HeaderQuickMetric(
        label = "Miles",
        value = "${stats.totalMilesRidden}",
        testTag = "header_total_miles",
      )
      HeaderQuickMetric(
        label = "Unique",
        value = "${stats.uniqueCorridorMilesRidden} mi",
        testTag = "header_unique_miles",
      )
    }
  }
}

@Composable
internal fun StorageErrorBanner(storageError: String) {
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.errorContainer)
        .border(1.dp, MaterialTheme.colorScheme.error)
        .padding(Spacing.md)
        .testTag("storage_error_banner"),
  ) {
    Text(
      text = storageError,
      color = MaterialTheme.colorScheme.onErrorContainer,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
internal fun BottomNavBar(uiState: StraphangerUiState, onSelectTab: (AppTab) -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
        .windowInsetsPadding(
          WindowInsets.navigationBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        )
        .padding(horizontal = 10.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AppTab.entries.forEach { tab ->
      val badgeCount = if (tab == AppTab.RIDES) uiState.stats.pendingReviewCount else 0
      NavTab(tab, selected = uiState.activeTab == tab, badgeCount, onClick = { onSelectTab(tab) })
    }
  }
}

@Composable
private fun NavTab(tab: AppTab, selected: Boolean, badgeCount: Int, onClick: () -> Unit) {
  Box(
    modifier =
      Modifier.clip(MaterialTheme.shapes.small)
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .testTag("nav_tab_${tab.name}"),
    contentAlignment = Alignment.Center,
  ) {
    val suffix = if (badgeCount > 0) " ($badgeCount)" else ""
    Text(
      text = "${tab.label}$suffix",
      color =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style =
        MaterialTheme.typography.labelMedium.copy(
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        ),
    )
  }
}

@Composable
private fun HeaderQuickMetric(
  label: String,
  value: String,
  testTag: String,
) {
  Column(
    horizontalAlignment = Alignment.End,
    modifier = Modifier.testTag(testTag),
  ) {
    Text(
      text = value,
      color = MaterialTheme.colorScheme.tertiary,
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold).tabular(),
    )
    Text(
      text = label,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}
