package com.straphanger.app.ui.rides

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RouteChoice
import com.straphanger.app.data.local.nextRideToCombine
import com.straphanger.app.ui.CardTitle
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.primaryButtonColors

@Composable
fun RidesHistoryAndLoggerScreen(
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  onUpdateLogForm: (String, String?, String, String?, Boolean?) -> Unit,
  onSubmitQuickRide: () -> Unit,
  onConfirmAmbiguousRide: (String, String, Boolean) -> Unit,
  onKeepRideAsIs: (String) -> Unit = {},
  onDeleteRide: (String) -> Unit = {},
  onChangeRideStations: (String, String, String, RouteChoice?) -> Boolean = { _, _, _, _ -> false },
  onCombineRides: (String, String) -> Boolean = { _, _ -> false },
  onClearRouteFilter: () -> Unit = {},
) {
  val pendingReviewRides = uiState.rides.filter { it.ride.needsReview }
  val nextRides = remember(uiState.rides, graphEngine) { nextRidesOf(uiState.rides, graphEngine) }
  var rideIdToDelete by remember { mutableStateOf<String?>(null) }
  var rideIdToEdit by remember { mutableStateOf<String?>(null) }
  var ridesToCombine by remember {
    mutableStateOf<Pair<CompleteRideRecord, CompleteRideRecord>?>(null)
  }
  val reviewActions =
    RideReviewActions(
      onConfirmAmbiguousRide = onConfirmAmbiguousRide,
      onKeepRideAsIs = onKeepRideAsIs,
      onEditStations = { rideIdToEdit = it },
      onCombine = { first, second -> ridesToCombine = first to second },
    )

  EditStationsHost(
    rec = uiState.rides.firstOrNull { it.ride.rideId == rideIdToEdit },
    graphEngine = graphEngine,
    onChange = onChangeRideStations,
    onDone = { rideIdToEdit = null },
  )
  CombineRidesHost(ridesToCombine, graphEngine, onCombineRides) { ridesToCombine = null }
  DeleteRideHost(rideIdToDelete, onDeleteRide) { rideIdToDelete = null }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
        .testTag("rides_history_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Rides that need review: express or local, or a station name that matched several stations
    if (pendingReviewRides.isNotEmpty()) {
      NeedsReviewBanner(pendingReviewRides, graphEngine, nextRides, reviewActions)
    }
    QuickTripLoggerCard(
      uiState = uiState,
      graphEngine = graphEngine,
      onUpdateLogForm = onUpdateLogForm,
      onSubmitQuickRide = onSubmitQuickRide,
    )
    RecordedRides(uiState, graphEngine, nextRides, reviewActions, onClearRouteFilter) {
      rideIdToDelete = it
    }
  }
}

/** The ride each ride could be combined with, by ride id; null where there is none. */
private fun nextRidesOf(
  rides: List<CompleteRideRecord>,
  graphEngine: SubwayGraphEngine,
): Map<String, CompleteRideRecord?> = rides.associate {
  it.ride.rideId to nextRideToCombine(rides, it.ride, graphEngine)
}

/** Every recorded ride, or only those on the line being filtered on. */
@Composable
private fun RecordedRides(
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  nextRides: Map<String, CompleteRideRecord?>,
  reviewActions: RideReviewActions,
  onClearRouteFilter: () -> Unit,
  onDelete: (String) -> Unit,
) {
  val routeFilter = uiState.ridesRouteFilter
  val shownRides =
    if (routeFilter == null) uiState.rides
    else uiState.rides.filter { rec -> rec.legs.any { it.routeId == routeFilter } }
  CardTitle("Recorded Subway Rides (${shownRides.size})")
  if (routeFilter != null) RouteFilterChip(routeFilter, onClear = onClearRouteFilter)
  shownRides.forEach { rec ->
    HighlightedWhenOpenedFromNotification(
      highlighted = rec.ride.rideId == uiState.highlightedRideId,
    ) {
      RideHistoryItem(
        rec,
        graphEngine,
        next = nextRides[rec.ride.rideId],
        actions = reviewActions,
        onDelete = { onDelete(rec.ride.rideId) },
      )
    }
  }
}

/** Frames [content] and scrolls it into view while [highlighted]; otherwise just shows it. */
@Composable
private fun HighlightedWhenOpenedFromNotification(
  highlighted: Boolean,
  content: @Composable () -> Unit,
) {
  if (!highlighted) {
    content()
    return
  }
  val requester = remember { BringIntoViewRequester() }
  LaunchedEffect(Unit) { requester.bringIntoView() }
  Box(
    modifier =
      Modifier.bringIntoViewRequester(requester)
        .border(2.dp, MtaColors.Success, MaterialTheme.shapes.medium)
        .padding(2.dp)
        .testTag("ride_row_highlighted"),
  ) {
    content()
  }
}

/** Asks before combining the pair in [rides]; nothing when null. */
@Composable
private fun CombineRidesHost(
  rides: Pair<CompleteRideRecord, CompleteRideRecord>?,
  graphEngine: SubwayGraphEngine,
  onCombine: (String, String) -> Boolean,
  onDone: () -> Unit,
) {
  rides?.let { (first, second) ->
    CombineRidesDialog(
      first = first,
      second = second,
      graphEngine = graphEngine,
      onConfirm = {
        onDone()
        onCombine(first.ride.rideId, second.ride.rideId)
      },
      onDismiss = onDone,
    )
  }
}

/** Shows the station editor for [rec] until it is saved or dismissed; nothing when null. */
@Composable
private fun EditStationsHost(
  rec: CompleteRideRecord?,
  graphEngine: SubwayGraphEngine,
  onChange: (String, String, String, RouteChoice?) -> Boolean,
  onDone: () -> Unit,
) {
  if (rec != null) {
    val ride = rec.ride
    // The line the ride is on, once nothing is left to ask about it.
    val currentRoute =
      rec.legs
        .singleOrNull()
        ?.takeIf { ride.ambiguousCandidates.isEmpty() }
        ?.let { RouteChoice(it.routeId, it.isExpress) }
    EditStationsDialog(
      ride = ride,
      graphEngine = graphEngine,
      currentRoute = currentRoute,
      onSave = { origin, destination, choice ->
        onChange(ride.rideId, origin, destination, choice).also { saved -> if (saved) onDone() }
      },
      onDismiss = onDone,
    )
  }
}

/** Asks before deleting [rideId]; nothing when null. */
@Composable
private fun DeleteRideHost(rideId: String?, onDelete: (String) -> Unit, onDone: () -> Unit) {
  if (rideId == null) return
  val onConfirm = {
    onDone()
    onDelete(rideId)
  }
  val onDismiss = onDone
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete this ride?") },
    text = { Text("The ride is removed from this device. This cannot be undone.") },
    confirmButton = {
      TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm_delete_btn")) {
        Text("Delete")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_delete_btn")) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("delete_ride_dialog"),
  )
}

/** Supports origin, an optional transfer, destination, and express or local. */
@Composable
private fun QuickTripLoggerCard(
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  onUpdateLogForm: (String, String?, String, String?, Boolean?) -> Unit,
  onSubmitQuickRide: () -> Unit,
) {
  SectionCard(spacing = 10.dp, modifier = Modifier.testTag("quick_trip_logger_card")) {
    CardTitle("Quick Trip Logger (Single or Multi-Leg Transfer)")
    val transName =
      uiState.logTransferStationId?.let { graphEngine.getStation(it)?.name }
        ?: "None (Direct / Auto)"
    Text(
      text =
        "Origin: ${graphEngine.stationName(uiState.logOriginStationId)}  →  " +
          "Transfer: $transName  →  " +
          "Destination: ${graphEngine.stationName(uiState.logDestinationStationId)}",
      color = MaterialTheme.colorScheme.tertiary,
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    Text(
      text = "Popular Trip Presets (or tap any station on the Map tab):",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    TripPresets(onUpdateLogForm)
    Button(
      onClick = onSubmitQuickRide,
      colors = primaryButtonColors(),
      modifier = Modifier.fillMaxWidth().testTag("submit_log_ride_btn"),
    ) {
      Text("Log Subway Ride")
    }
  }
}

/** One-tap presets that fill in the log form. */
@Composable
private fun TripPresets(onUpdateLogForm: (String, String?, String, String?, Boolean?) -> Unit) {
  Row(
    modifier = Modifier.horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    TRIP_PRESETS.forEach { preset ->
      PresetChip(
        label = preset.label,
        testTag = preset.testTag,
        onClick = {
          onUpdateLogForm(
            preset.originStationId,
            preset.transferStationId,
            preset.destinationStationId,
            preset.routeId,
            preset.express,
          )
        },
      )
    }
  }
}

@Composable
private fun PresetChip(
  label: String,
  testTag: String,
  onClick: () -> Unit,
) {
  AssistChip(
    onClick = onClick,
    label = { Text(text = label) },
    shape = MaterialTheme.shapes.small,
    colors =
      AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
      ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.testTag(testTag),
  )
}
