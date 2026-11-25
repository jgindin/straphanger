package com.straphanger.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.confirmButtonColors
import com.straphanger.app.ui.theme.primaryButtonColors
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_close

/**
 * The selected station's details and the buttons that start or end a ride there, as a sheet pinned
 * to the bottom of the map. It is not a modal sheet: a modal one's scrim would stop the map being
 * panned and another station being tapped while it is open. [onClose] clears the selection.
 */
@OptIn(ExperimentalMaterial3Api::class) // BottomSheetDefaults.DragHandle is still experimental
@Composable
internal fun BoxScope.StationDetailSheet(
  station: SubwayStation,
  stats: SubwayStatsSnapshot,
  onClose: () -> Unit,
  onStartRideFromStation: (String) -> Unit,
  onEndRideAtStation: (String) -> Unit,
) {
  val isStopped = station.id in stats.visitedStationIdsStoppedAt
  val isBoarded = station.id in stats.visitedStationIdsBoardedExited
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = MaterialTheme.shapes.extraLarge,
    tonalElevation = 3.dp,
    shadowElevation = 3.dp,
    modifier =
      Modifier.align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(Spacing.md)
        .testTag("station_detail_sheet"),
  ) {
    Column(modifier = Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg)) {
      Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Decorative: the sheet is not draggable, so a screen reader need not hear a handle.
        Box(modifier = Modifier.clearAndSetSemantics {}) { BottomSheetDefaults.DragHandle() }
        IconButton(
          onClick = onClose,
          modifier = Modifier.align(Alignment.CenterEnd).testTag("station_detail_close"),
        ) {
          Icon(painterResource(Res.drawable.ic_close), contentDescription = "Close")
        }
      }
      StationHeader(station)
      Spacer(modifier = Modifier.height(Spacing.sm))
      Text(
        text =
          when {
            isBoarded -> "Status: Visited (Boarded / Exited / Transferred here)"
            isStopped -> "Status: Stopped Through on a train"
            else -> "Status: Unvisited station"
          },
        color = if (isStopped) MtaColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
      )
      Spacer(modifier = Modifier.height(Spacing.md))
      LogRideButtons(station.id, onStartRideFromStation, onEndRideAtStation)
    }
  }
}

@Composable
private fun LogRideButtons(
  stationId: String,
  onStartRideFromStation: (String) -> Unit,
  onEndRideAtStation: (String) -> Unit,
) {
  Row(
    modifier = Modifier.height(IntrinsicSize.Min),
    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    Button(
      onClick = { onStartRideFromStation(stationId) },
      colors = primaryButtonColors(),
      modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
      Text("Log Ride From Here", textAlign = TextAlign.Center)
    }
    Button(
      onClick = { onEndRideAtStation(stationId) },
      colors = confirmButtonColors(),
      modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
      Text("Log Ride To Here", textAlign = TextAlign.Center)
    }
  }
}

@Composable
private fun StationHeader(station: SubwayStation) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = station.name,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = "${station.borough.displayName} • Complex: ${station.complexName}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      station.daytimeRoutes.forEach { rId ->
        MtaRouteBullet(routeId = rId, size = 24.dp, fontSize = 11.sp)
      }
    }
  }
}
