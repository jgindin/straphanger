package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.ui.theme.Spacing
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_close
import straphanger.shared.generated.resources.ic_search

/**
 * A full-screen station search for one of the log sheet's fields, titled [title]. [onPick] gets the
 * chosen station; [onClear], when given, offers "Clear" to leave the field empty.
 */
@Composable
internal fun StationPickerDialog(
  title: String,
  graphEngine: SubwayGraphEngine,
  onPick: (SubwayStation) -> Unit,
  onDismiss: () -> Unit,
  onClear: (() -> Unit)? = null,
) {
  var query by rememberSaveable { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  // Ready to type as soon as it opens.
  LaunchedEffect(focus) { focus.requestFocus() }
  val choices = remember(query) { stationChoices(graphEngine, query, nearStationId = null) }
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .padding(Spacing.lg)
          .testTag("log_station_search"),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      PickerHeader(title, onDismiss, onClear)
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search stations") },
        leadingIcon = { Icon(painterResource(Res.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
          if (query.isNotEmpty()) {
            IconButton(
              onClick = { query = "" },
              modifier = Modifier.testTag("log_station_search_clear"),
            ) {
              Icon(painterResource(Res.drawable.ic_close), contentDescription = "Clear search")
            }
          }
        },
        singleLine = true,
        modifier =
          Modifier.fillMaxWidth().focusRequester(focus).testTag("log_station_search_field"),
      )
      StationResults(choices.map { it.station }, onPick)
    }
  }
}

/** The title, with Close and, for the optional change, Clear. */
@Composable
private fun PickerHeader(title: String, onDismiss: () -> Unit, onClear: (() -> Unit)?) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    IconButton(onClick = onDismiss, modifier = Modifier.testTag("log_station_search_close")) {
      Icon(painterResource(Res.drawable.ic_close), contentDescription = "Close")
    }
    Text(
      title,
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.weight(1f),
    )
    if (onClear != null) {
      TextButton(onClick = onClear, modifier = Modifier.testTag("log_station_clear")) {
        Text("Clear")
      }
    }
  }
}

@Composable
private fun StationResults(stations: List<SubwayStation>, onPick: (SubwayStation) -> Unit) {
  if (stations.isEmpty()) {
    Text(
      "No stations match.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
    return
  }
  LazyColumn(modifier = Modifier.fillMaxWidth().testTag("log_station_results")) {
    items(stations, key = { it.id }) { station ->
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { onPick(station) }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
            .testTag("log_station_result_${station.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            station.name,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
          )
          Text(
            station.borough.displayName,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        StationBullets(station.daytimeRoutes)
      }
    }
  }
}
