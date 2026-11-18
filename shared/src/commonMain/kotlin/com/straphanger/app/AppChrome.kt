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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.AppTab
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaColors

@Composable
internal fun StraphangerTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme =
      darkColorScheme(
        background = MtaColors.DarkBg,
        surface = MtaColors.CardBg,
        primary = Color(0xFF0039A6),
        onPrimary = Color.White,
        secondary = Color(0xFF2B3548),
        onSecondary = Color.White,
        onBackground = MtaColors.TextPrimary,
        onSurface = MtaColors.TextPrimary,
        onSurfaceVariant = MtaColors.TextSecondary,
      ),
  ) {
    CompositionLocalProvider(LocalContentColor provides MtaColors.TextPrimary, content = content)
  }
}

@Composable
internal fun AppHeader(stats: SubwayStatsSnapshot) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(Color(0xFF10151F))
        .windowInsetsPadding(
          WindowInsets.statusBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        )
        .padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(
        text = "STRAPHANGER",
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.testTag("header_title"),
      )
      Text(
        text = "NYC Subway Coverage & Ride Tracker",
        color = MtaColors.TextSecondary,
        fontSize = 11.sp,
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
        .background(Color(0xFF2A1416))
        .border(1.dp, Color(0xFFD64545))
        .padding(12.dp)
        .testTag("storage_error_banner"),
  ) {
    Text(text = storageError, color = Color.White, fontSize = 12.sp)
  }
}

@Composable
internal fun BottomNavBar(uiState: StraphangerUiState, onSelectTab: (AppTab) -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(Color(0xFF10151F))
        .border(1.dp, MtaColors.CardBorder)
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
      Modifier.clip(RoundedCornerShape(10.dp))
        .background(if (selected) Color(0xFF0039A6) else Color.Transparent)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .testTag("nav_tab_${tab.name}"),
    contentAlignment = Alignment.Center,
  ) {
    val suffix = if (badgeCount > 0) " ($badgeCount)" else ""
    Text(
      text = "${tab.label}$suffix",
      color = if (selected) Color.White else MtaColors.TextSecondary,
      fontSize = 12.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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
      color = MtaColors.AccentGold,
      fontSize = 13.sp,
      fontWeight = FontWeight.ExtraBold,
    )
    Text(
      text = label,
      color = MtaColors.TextSecondary,
      fontSize = 10.sp,
    )
  }
}
