package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.straphanger.app.data.gtfs.AmbiguousRouteCandidate
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RouteChoice
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.confirmButtonColors
import com.straphanger.app.ui.theme.tabular

/** Which end of the ride the station search is choosing. */
private enum class RideEnd(val label: String) {
  START("From"),
  END("To"),
}

/**
 * Lets the rider change where [ride] started and ended and, where an express and a local both run
 * between the stations, which one they took. [onSave] gets the stations and the chosen line, or
 * null to let the app decide; it returns false when the pair of stations cannot be used, in which
 * case the dialog stays open and says so. [currentRoute] is the line the ride is on, chosen at the
 * start while the stations are unchanged.
 */
@Composable
internal fun EditStationsDialog(
  ride: RideEntity,
  graphEngine: SubwayGraphEngine,
  onSave: (origin: String, destination: String, choice: RouteChoice?) -> Boolean,
  onDismiss: () -> Unit,
  currentRoute: RouteChoice? = null,
) {
  var origin by remember { mutableStateOf(ride.originStationId) }
  var destination by remember { mutableStateOf(ride.destinationStationId) }
  var choosing by remember { mutableStateOf(RideEnd.START) }
  var query by remember { mutableStateOf("") }
  var failed by remember { mutableStateOf(false) }
  val chosenId = if (choosing == RideEnd.START) origin else destination
  // Sorted by distance from the station this end had when the dialog opened, so the list does not
  // reorder under the rider's finger as they pick.
  val anchorId = if (choosing == RideEnd.START) ride.originStationId else ride.destinationStationId
  val choices = remember(query, anchorId) { stationChoices(graphEngine, query, anchorId) }
  val routes =
    remember(origin, destination) { routeChoices(graphEngine, ride, origin, destination) }
  val unchanged = origin == ride.originStationId && destination == ride.destinationStationId
  var route by
    remember(origin, destination) {
      mutableStateOf(currentRoute?.takeIf { unchanged && it in routes.map(::routeOf) })
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    // The platform's default width never settles around a text field in a tall window under
    // Robolectric; AlertDialog still limits its own width.
    properties = DialogProperties(usePlatformDefaultWidth = false),
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    textContentColor = MaterialTheme.colorScheme.onSurface,
    shape = MaterialTheme.shapes.large,
    title = { Text("Edit stations", style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EndChoosers(graphEngine, origin, destination, choosing) { end ->
          choosing = end
          query = ""
        }
        if (routes.isNotEmpty()) RouteChips(routes, route) { route = it }
        SearchField(choosing, query) { query = it }
        if (failed) {
          Text(
            "No route joins those stations.",
            color = MtaColors.Warning,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("edit_stations_error"),
          )
        }
        StationMatches(choices, chosenId) { station ->
          if (choosing == RideEnd.START) origin = station.id else destination = station.id
          failed = false
        }
      }
    },
    confirmButton = { SaveButton { failed = !onSave(origin, destination, route) } },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("edit_stations_cancel")) {
        Text("Cancel")
      }
    },
    modifier = Modifier.padding(horizontal = 16.dp).testTag("edit_stations_dialog"),
  )
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
  Button(
    onClick = onClick,
    colors = confirmButtonColors(),
    modifier = Modifier.testTag("edit_stations_save"),
  ) {
    Text("Save")
  }
}

/** The From and To choosers; one of them, [choosing], is the end the search fills in. */
@Composable
private fun EndChoosers(
  graphEngine: SubwayGraphEngine,
  origin: String,
  destination: String,
  choosing: RideEnd,
  onChoose: (RideEnd) -> Unit,
) {
  Column(
    modifier = Modifier.selectableGroup(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    RideEnd.entries.forEach { end ->
      val id = if (end == RideEnd.START) origin else destination
      EndChooser(end, graphEngine.getStation(id), id, selected = choosing == end) { onChoose(end) }
    }
  }
}

@Composable
private fun EndChooser(
  end: RideEnd,
  station: SubwayStation?,
  stationId: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = 48.dp)
        .clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(
          width = if (selected) 2.dp else 1.dp,
          color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          shape = MaterialTheme.shapes.small,
        )
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = Spacing.md, vertical = 10.dp)
        .testTag("edit_stations_choose_${end.name.lowercase()}"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      end.label,
      color =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.padding(end = 2.dp),
    )
    Text(
      station?.name ?: stationId,
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.weight(1f),
    )
    station?.let { StationBullets(it.daytimeRoutes) }
  }
}

@Composable
private fun SearchField(choosing: RideEnd, query: String, onQueryChange: (String) -> Unit) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    label = { Text("Search for the ${choosing.label.lowercase()} station") },
    singleLine = true,
    colors =
      OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier = Modifier.fillMaxWidth().testTag("edit_stations_search"),
  )
}

@Composable
private fun StationMatches(
  choices: List<StationChoice>,
  chosenId: String,
  onPick: (SubwayStation) -> Unit,
) {
  Column(
    modifier =
      Modifier.heightIn(max = 280.dp)
        .verticalScroll(rememberScrollState())
        .testTag("edit_stations_matches"),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    if (choices.isEmpty()) {
      Text(
        "No stations match.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
    choices.forEach { (station, miles) ->
      val chosen = station.id == chosenId
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
              if (chosen) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .clickable(role = Role.Button) { onPick(station) }
            .padding(horizontal = 8.dp, vertical = 9.dp)
            .testTag("edit_stations_option_${station.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          station.name,
          color =
            if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
            ),
          modifier = Modifier.weight(1f),
        )
        StationBullets(station.daytimeRoutes)
        if (miles != null) {
          Text(
            distanceText(miles),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.tabular(),
          )
        }
      }
    }
  }
}

/** A station's line bullets, without the express variants that draw the same. */
@Composable
internal fun StationBullets(routes: List<String>) {
  Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    bulletRoutes(routes).forEach { MtaRouteBullet(routeId = it, size = 20.dp, fontSize = 10.sp) }
  }
}

/**
 * The ways to ride from [origin] to [destination] at [ride]'s time, when there is more than one (an
 * express and a local); empty otherwise.
 */
private fun routeChoices(
  graphEngine: SubwayGraphEngine,
  ride: RideEntity,
  origin: String,
  destination: String,
): List<AmbiguousRouteCandidate> =
  if (origin == destination) emptyList()
  else
    graphEngine
      .resolveOriginToDestinationAuto(
        originStationId = origin,
        destinationStationId = destination,
        timestampEpochMs = ride.startTimeEpochMs,
        actualDurationMinutes = ride.durationMinutes,
      )
      .ambiguousCandidates

private fun routeOf(candidate: AmbiguousRouteCandidate) =
  RouteChoice(candidate.routeId, candidate.isExpress)

/** "Let the app decide", then one chip per line; [chosen] is null for the app to decide. */
@Composable
private fun RouteChips(
  routes: List<AmbiguousRouteCandidate>,
  chosen: RouteChoice?,
  onChoose: (RouteChoice?) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      "Which train?",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelLarge,
    )
    FlowRow(
      modifier = Modifier.selectableGroup(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      RouteChip("Let the app decide", null, chosen == null, "edit_stations_route_auto") {
        onChoose(null)
      }
      routes.forEachIndexed { index, candidate ->
        val choice = routeOf(candidate)
        RouteChip(
          candidate.label,
          candidate.routeId,
          chosen == choice,
          "edit_stations_route_$index",
        ) {
          onChoose(choice)
        }
      }
    }
  }
}

@Composable
private fun RouteChip(
  label: String,
  routeId: String?,
  selected: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(
          width = if (selected) 2.dp else 1.dp,
          color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          shape = MaterialTheme.shapes.small,
        )
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 7.dp)
        .testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    routeId?.let { MtaRouteBullet(routeId = it, size = 20.dp, fontSize = 10.sp) }
    Text(
      label,
      color =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      style =
        MaterialTheme.typography.bodyMedium.copy(
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        ),
    )
  }
}
