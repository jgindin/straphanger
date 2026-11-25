package com.straphanger.app.ui.rides

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaRouteBullet
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.primaryButtonColors

/** The log sheet's station fields, each opening the station search. */
private enum class LogField(val label: String, val tag: String) {
  FROM("From", "log_from_field"),
  TRANSFER("Change at (optional)", "log_transfer_field"),
  TO("To", "log_to_field"),
}

/**
 * Logs a ride: From, an optional change and To, the rider's own trips as one-tap chips, and "Log
 * ride". The stations are the log form's in [uiState]; [onUpdateLogForm] changes one and keeps the
 * rest. [onSubmit] logs the ride, which closes the sheet; [onDismiss] closes it without logging.
 */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet
@Composable
internal fun LogRideSheet(
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  suggestions: List<TripSuggestion>,
  onUpdateLogForm: (String?, String?, String?, String?, Boolean?) -> Unit,
  onSubmit: () -> Unit,
  onDismiss: () -> Unit,
) {
  var picking by rememberSaveable { mutableStateOf<LogField?>(null) }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.testTag("log_ride_sheet"),
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = Spacing.lg)
          .padding(bottom = Spacing.lg)
          .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      Text(
        "Log a ride",
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
      )
      LogField.entries.forEach { field ->
        StationField(field, uiState.stationIdOf(field)?.let(graphEngine::getStation)) {
          picking = field
        }
      }
      if (suggestions.isNotEmpty()) TripSuggestionChips(suggestions, onUpdateLogForm)
      val origin = uiState.logOriginStationId
      val destination = uiState.logDestinationStationId
      Button(
        onClick = onSubmit,
        enabled = origin != null && destination != null && origin != destination,
        colors = primaryButtonColors(),
        modifier = Modifier.fillMaxWidth().testTag("submit_log_ride_btn"),
      ) {
        Text("Log ride")
      }
    }
  }
  picking?.let { field ->
    StationSearch(field, uiState, graphEngine, onUpdateLogForm) { picking = null }
  }
}

private fun StraphangerUiState.stationIdOf(field: LogField): String? =
  when (field) {
    LogField.FROM -> logOriginStationId
    LogField.TRANSFER -> logTransferStationId
    LogField.TO -> logDestinationStationId
  }

/**
 * The search for [field]. Choosing a station sets that field and keeps the other stations; the
 * preferred route and express choice, which belonged to the old stations, are cleared.
 */
@Composable
private fun StationSearch(
  field: LogField,
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  onUpdateLogForm: (String?, String?, String?, String?, Boolean?) -> Unit,
  onDone: () -> Unit,
) {
  fun update(origin: String?, transfer: String?, destination: String?) {
    onUpdateLogForm(origin, transfer, destination, null, null)
    onDone()
  }
  val origin = uiState.logOriginStationId
  val transfer = uiState.logTransferStationId
  val destination = uiState.logDestinationStationId
  StationPickerDialog(
    title = field.label,
    graphEngine = graphEngine,
    onPick = { station ->
      when (field) {
        LogField.FROM -> update(station.id, transfer, destination)
        LogField.TRANSFER -> update(origin, station.id, destination)
        LogField.TO -> update(origin, transfer, station.id)
      }
    },
    onDismiss = onDone,
    onClear = if (field == LogField.TRANSFER) ({ update(origin, null, destination) }) else null,
  )
}

/**
 * A read-only outlined field showing [station]'s name and lines; tapping it opens the search. The
 * whole field is one button to a screen reader, read as its label and station.
 */
@Composable
private fun StationField(field: LogField, station: SubwayStation?, onClick: () -> Unit) {
  val name = station?.name.orEmpty()
  Box(modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = name,
      onValueChange = {},
      readOnly = true,
      enabled = false,
      singleLine = true,
      label = { Text(field.label) },
      placeholder = { Text("None") },
      trailingIcon = station?.let { { StationBullets(it.daytimeRoutes) } },
      colors = enabledLookingColors(),
      // The button drawn over it speaks for it.
      modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
    )
    Box(
      modifier =
        Modifier.matchParentSize()
          .testTag(field.tag)
          .clickable(role = Role.Button, onClick = onClick)
          .semantics { contentDescription = "${field.label}: ${name.ifEmpty { "none" }}" },
    )
  }
}

/** A disabled field (so it takes no focus or keyboard) drawn like an enabled one. */
@Composable
private fun enabledLookingColors() =
  OutlinedTextFieldDefaults.colors(
    disabledTextColor = MaterialTheme.colorScheme.onSurface,
    disabledBorderColor = MaterialTheme.colorScheme.outline,
    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
  )

/** "Your trips": one chip per trip the rider has taken, filling in the form when tapped. */
@Composable
private fun TripSuggestionChips(
  suggestions: List<TripSuggestion>,
  onUpdateLogForm: (String?, String?, String?, String?, Boolean?) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Text(
      "Your trips",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelLarge,
    )
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      suggestions.forEachIndexed { index, trip ->
        AssistChip(
          onClick = {
            onUpdateLogForm(
              trip.originStationId,
              trip.transferStationId,
              trip.destinationStationId,
              trip.routeId,
              null,
            )
          },
          label = { Text(trip.label) },
          leadingIcon =
            trip.routeId?.let { { MtaRouteBullet(routeId = it, size = 20.dp, fontSize = 10.sp) } },
          shape = MaterialTheme.shapes.small,
          colors =
            AssistChipDefaults.assistChipColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              labelColor = MaterialTheme.colorScheme.onSurface,
            ),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.testTag("trip_suggestion_$index"),
        )
      }
    }
  }
}
