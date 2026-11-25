package com.straphanger.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.ui.MapColorMode
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaRouteBullet
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.WithTooltip
import com.straphanger.app.ui.theme.tabular
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_add
import straphanger.shared.generated.resources.ic_check
import straphanger.shared.generated.resources.ic_remove

/** The route filter bar's lines, in the MTA's trunk order. */
private val FILTER_ROUTES =
  listOf(
    "1",
    "2",
    "3",
    "4",
    "5",
    "6",
    "7",
    "A",
    "C",
    "E",
    "B",
    "D",
    "F",
    "M",
    "G",
    "J",
    "Z",
    "L",
    "N",
    "Q",
    "R",
    "W",
    "GS",
    "FS",
    "H",
    "SI",
  )

/** The colour mode selector and the route bullet filter bar. */
@Composable
internal fun BoxScope.MapControlsOverlay(
  uiState: StraphangerUiState,
  onColorModeChange: (MapColorMode) -> Unit,
  onRouteFilterSelect: (String?) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md).align(Alignment.TopCenter)) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .clip(MaterialTheme.shapes.medium)
          .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f))
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
          .padding(Spacing.sm),
      verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
      ColorModeSelector(uiState.mapColorMode, onColorModeChange, Modifier.fillMaxWidth())
      Text(
        text =
          "${uiState.stats.uniqueCorridorMilesRidden} mi " +
            "(${uiState.stats.systemCompletionPercent}%)",
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelLarge.tabular(),
        modifier = Modifier.testTag("map_header_unique_miles"),
      )
    }
    Spacer(modifier = Modifier.height(Spacing.sm))
    RouteFilterBar(uiState.selectedRouteFilter, onRouteFilterSelect)
  }
}

@Composable
private fun ColorModeSelector(
  selected: MapColorMode,
  onColorModeChange: (MapColorMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  SingleChoiceSegmentedButtonRow(modifier = modifier) {
    MapColorMode.entries.forEachIndexed { index, mode ->
      SegmentedButton(
        selected = selected == mode,
        onClick = { onColorModeChange(mode) },
        shape = SegmentedButtonDefaults.itemShape(index, MapColorMode.entries.size),
        modifier = Modifier.testTag("map_mode_${mode.name}"),
      ) {
        Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun RouteFilterBar(selectedRoute: String?, onRouteFilterSelect: (String?) -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        .horizontalScroll(rememberScrollState())
        .selectableGroup()
        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FilterChip(
      selected = selectedRoute == null,
      onClick = { onRouteFilterSelect(null) },
      label = { Text("All Lines") },
      leadingIcon = {
        if (selectedRoute == null) {
          Icon(painterResource(Res.drawable.ic_check), contentDescription = null)
        }
      },
      modifier = Modifier.testTag("filter_route_ALL").semantics { role = Role.RadioButton },
    )
    FILTER_ROUTES.forEach { routeId ->
      val isSelected = selectedRoute == routeId
      FilterChip(
        selected = isSelected,
        onClick = { onRouteFilterSelect(routeId) },
        // The bullet is the label.
        label = { MtaRouteBullet(routeId = routeId, size = 24.dp, fontSize = 12.sp) },
        border =
          FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = isSelected,
            borderWidth = 1.dp,
            selectedBorderWidth = 3.dp,
          ),
        modifier =
          Modifier.testTag("filter_route_$routeId").semantics {
            role = Role.RadioButton
            contentDescription = "$routeId line"
          },
      )
    }
  }
}

/** The + and − buttons. */
@Composable
internal fun BoxScope.MapSideControls(
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
) {
  Column(
    modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.md),
    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    horizontalAlignment = Alignment.End,
  ) {
    ZoomButton(Res.drawable.ic_add, "Zoom in", onZoomIn, "map_zoom_in")
    ZoomButton(Res.drawable.ic_remove, "Zoom out", onZoomOut, "map_zoom_out")
  }
}

@Composable
private fun ZoomButton(
  icon: DrawableResource,
  description: String,
  onClick: () -> Unit,
  testTag: String,
) {
  WithTooltip(description) {
    FilledIconButton(
      onClick = onClick,
      colors =
        IconButtonDefaults.filledIconButtonColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainer,
          contentColor = MaterialTheme.colorScheme.onSurface,
        ),
      modifier = Modifier.testTag(testTag),
    ) {
      Icon(painterResource(icon), contentDescription = description)
    }
  }
}
