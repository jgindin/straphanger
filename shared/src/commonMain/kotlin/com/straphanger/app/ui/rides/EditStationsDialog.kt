package com.straphanger.app.ui.rides

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.data.local.RideEntity

private const val MAX_STATION_MATCHES = 40

/** Which end of the ride the station search is choosing. */
private enum class RideEnd(val label: String) {
  START("From"),
  END("To"),
}

/**
 * Lets the rider change where [ride] started and ended. [onSave] returns false when the pair of
 * stations cannot be used, in which case the dialog stays open and says so.
 */
@Composable
internal fun EditStationsDialog(
  ride: RideEntity,
  graphEngine: SubwayGraphEngine,
  onSave: (origin: String, destination: String) -> Boolean,
  onDismiss: () -> Unit,
) {
  var origin by remember { mutableStateOf(ride.originStationId) }
  var destination by remember { mutableStateOf(ride.destinationStationId) }
  var choosing by remember { mutableStateOf(RideEnd.START) }
  var query by remember { mutableStateOf("") }
  var failed by remember { mutableStateOf(false) }
  val matches =
    remember(query) {
      graphEngine.searchStations(query).distinctBy { it.name }.take(MAX_STATION_MATCHES)
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    // The platform's default width never settles around a text field in a tall window under
    // Robolectric; AlertDialog still limits its own width.
    properties = DialogProperties(usePlatformDefaultWidth = false),
    title = { Text("Edit stations") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
          RideEnd.entries.forEach { end ->
            val id = if (end == RideEnd.START) origin else destination
            EndChooser(end, graphEngine.stationName(id), selected = choosing == end) {
              choosing = end
              query = ""
            }
          }
        }
        SearchField(choosing, query) { query = it }
        if (failed) {
          Text(
            "No route joins those stations.",
            color = Color.Red,
            modifier = Modifier.testTag("edit_stations_error"),
          )
        }
        StationMatches(matches) { station ->
          if (choosing == RideEnd.START) origin = station.id else destination = station.id
          failed = false
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { failed = !onSave(origin, destination) },
        modifier = Modifier.testTag("edit_stations_save"),
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("edit_stations_cancel")) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("edit_stations_dialog"),
  )
}

@Composable
private fun EndChooser(end: RideEnd, stationName: String, selected: Boolean, onClick: () -> Unit) {
  TextButton(
    onClick = onClick,
    modifier = Modifier.testTag("edit_stations_choose_${end.name.lowercase()}"),
  ) {
    Text(
      "${end.label}: $stationName",
      fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
      fontSize = 13.sp,
    )
  }
}

@Composable
private fun SearchField(choosing: RideEnd, query: String, onQueryChange: (String) -> Unit) {
  Text("Search ${choosing.label.lowercase()} station", fontSize = 12.sp)
  BasicTextField(
    value = query,
    onValueChange = onQueryChange,
    singleLine = true,
    textStyle = TextStyle(color = LocalContentColor.current, fontSize = 15.sp),
    cursorBrush = SolidColor(LocalContentColor.current),
    modifier =
      Modifier.fillMaxWidth()
        .border(1.dp, LocalContentColor.current.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
        .padding(10.dp)
        .testTag("edit_stations_search"),
  )
}

@Composable
private fun StationMatches(matches: List<SubwayStation>, onPick: (SubwayStation) -> Unit) {
  Column(
    modifier =
      Modifier.heightIn(max = 240.dp)
        .verticalScroll(rememberScrollState())
        .testTag("edit_stations_matches"),
  ) {
    matches.forEach { station ->
      Text(
        text = "${station.name}  (${station.daytimeRoutes.joinToString(" ")})",
        fontSize = 14.sp,
        modifier =
          Modifier.fillMaxWidth()
            .clickable { onPick(station) }
            .padding(vertical = 8.dp)
            .testTag("edit_stations_option_${station.id}"),
      )
    }
  }
}
