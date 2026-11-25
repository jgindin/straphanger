package com.straphanger.app.ui.rides

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The Rides list: [rows] in a lazy column, with each day's heading stuck to the top while its rides
 * scroll under it. A ride opened from a notification is scrolled into view.
 */
@OptIn(ExperimentalFoundationApi::class) // stickyHeader
@Composable
internal fun RideList(rows: List<RideListRow>, listState: LazyListState, content: RideListContent) {
  val highlightedIndex = rows.indexOfFirst {
    it is RideListRow.Ride && it.rec.ride.rideId == content.highlightedRideId
  }
  LaunchedEffect(content.highlightedRideId, highlightedIndex >= 0) {
    // One row up, so the day's heading (or the ride before) is not drawn over the ride.
    if (highlightedIndex >= 0) listState.scrollToItem((highlightedIndex - 1).coerceAtLeast(0))
  }
  StaysAtTopAsRowsComeInAbove(rows.firstOrNull()?.key, listState)
  LazyColumn(
    state = listState,
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .testTag("rides_history_screen"),
    // Room at the bottom so the Log ride button does not cover the last ride.
    contentPadding =
      PaddingValues(
        start = Spacing.lg,
        end = Spacing.lg,
        top = Spacing.lg,
        bottom = Spacing.lg + LogRideFabClearance,
      ),
    verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    rows.forEach { row ->
      when (row) {
        is RideListRow.DayHeader -> stickyHeader(key = row.key) { DayHeading(row.heading) }
        else -> item(key = row.key) { RideListItem(row, content) }
      }
    }
  }
}

/**
 * Keeps [listState] at the top when the first row changes while it is there. The list keeps its
 * first visible row in place as rows come in above it, so the review banner or the line filter
 * would otherwise land above the screen, out of sight.
 */
@Composable
private fun StaysAtTopAsRowsComeInAbove(firstKey: String?, listState: LazyListState) {
  remember(firstKey) {
    // Read where the list was before these rows are laid out, without recomposing as it scrolls.
    Snapshot.withoutReadObservation {
      if (listState.firstVisibleItemIndex == 0) listState.requestScrollToItem(0)
    }
  }
}

@Composable
private fun RideListItem(row: RideListRow, content: RideListContent) {
  when (row) {
    RideListRow.ReviewBanner ->
      NeedsReviewBanner(
        content.pendingReviewRides,
        content.graphEngine,
        content.nextRides,
        content.reviewActions,
      )
    is RideListRow.RouteFilter -> RouteFilterChip(row.routeId, onClear = content.onClearRouteFilter)
    is RideListRow.Count ->
      Text(
        row.text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("rides_count"),
      )
    is RideListRow.Ride -> RideRow(row.rec, content)
    RideListRow.Empty -> EmptyRides()
    is RideListRow.DayHeader -> DayHeading(row.heading)
  }
}

@Composable
private fun RideRow(rec: CompleteRideRecord, content: RideListContent) {
  val rideId = rec.ride.rideId
  HighlightedWhenOpenedFromNotification(highlighted = rideId == content.highlightedRideId) {
    SwipeToDelete(rideId, onDelete = { content.onDelete(rec) }) {
      RideHistoryItem(
        rec,
        content.graphEngine,
        next = content.nextRides[rideId],
        actions = content.reviewActions,
        onDelete = { content.onDelete(rec) },
      )
    }
  }
}

/** A day's heading, on the screen's background so the rides scrolling under it are hidden. */
@Composable
private fun DayHeading(heading: String) {
  Text(
    heading,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelLarge,
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .padding(vertical = Spacing.xs)
        .testTag("ride_day_heading"),
  )
}

@Composable
private fun EmptyRides() {
  Text(
    "No rides yet. Tap + to log one, or turn on ride capture in Settings.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag("rides_empty"),
  )
}

/**
 * Frames [content] while [highlighted] (a ride opened from a notification); otherwise just shows
 * it.
 */
@Composable
private fun HighlightedWhenOpenedFromNotification(
  highlighted: Boolean,
  content: @Composable () -> Unit,
) {
  if (!highlighted) {
    content()
    return
  }
  Box(
    modifier =
      Modifier.border(2.dp, MtaColors.Success, MaterialTheme.shapes.medium)
        .padding(2.dp)
        .testTag("ride_row_highlighted"),
  ) {
    content()
  }
}

/**
 * Swiping [content] from the end to the start reveals a red "Delete" and then deletes the ride.
 * When [onDelete] reports the deletion was not saved, the row slides back.
 */
@OptIn(ExperimentalMaterial3Api::class) // SwipeToDismissBox
@Composable
private fun SwipeToDelete(
  rideId: String,
  onDelete: () -> Boolean,
  content: @Composable () -> Unit,
) {
  val threshold = SwipeToDismissBoxDefaults.positionalThreshold
  // Not saved: the list keeps a deleted row's saved state by its key, so a saved state would bring
  // a ride put back by Undo back swiped away.
  val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
  val scope = rememberCoroutineScope()
  val currentOnDelete by rememberUpdatedState(onDelete)
  // The box runs this once each time the row settles dismissed; one lambda for the row's life, so
  // a recomposition does not run it again.
  val onDismiss: (SwipeToDismissBoxValue) -> Unit = remember {
    { value ->
      if (value == SwipeToDismissBoxValue.EndToStart && !currentOnDelete()) {
        scope.launch { state.reset() }
      }
    }
  }
  SwipeToDismissBox(
    state = state,
    enableDismissFromStartToEnd = false,
    onDismiss = onDismiss,
    backgroundContent = {
      Box(
        modifier =
          Modifier.fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = Spacing.lg),
        contentAlignment = Alignment.CenterEnd,
      ) {
        Text(
          "Delete",
          color = MaterialTheme.colorScheme.onError,
          style = MaterialTheme.typography.labelLarge,
        )
      }
    },
    modifier = Modifier.testTag("swipe_ride_$rideId"),
  ) {
    content()
  }
}
