package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet

/** Which end of the ride the station search is choosing. */
private enum class RideEnd(val label: String) {
  START("From"),
  END("To"),
}

private val ChoiceBg = Color(0xFF1C2433)

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
  val chosenId = if (choosing == RideEnd.START) origin else destination
  // Sorted by distance from the station this end had when the dialog opened, so the list does not
  // reorder under the rider's finger as they pick.
  val anchorId = if (choosing == RideEnd.START) ride.originStationId else ride.destinationStationId
  val choices = remember(query, anchorId) { stationChoices(graphEngine, query, anchorId) }

  AlertDialog(
    onDismissRequest = onDismiss,
    // The platform's default width never settles around a text field in a tall window under
    // Robolectric; AlertDialog still limits its own width.
    properties = DialogProperties(usePlatformDefaultWidth = false),
    containerColor = MtaColors.CardBg,
    titleContentColor = MtaColors.TextPrimary,
    textContentColor = MtaColors.TextPrimary,
    shape = RoundedCornerShape(16.dp),
    title = { Text("Edit stations", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RideEnd.entries.forEach { end ->
          val id = if (end == RideEnd.START) origin else destination
          EndChooser(end, graphEngine.getStation(id), id, selected = choosing == end) {
            choosing = end
            query = ""
          }
        }
        SearchField(choosing, query) { query = it }
        if (failed) {
          Text(
            "No route joins those stations.",
            color = MtaColors.AccentGold,
            fontSize = 12.sp,
            modifier = Modifier.testTag("edit_stations_error"),
          )
        }
        StationMatches(choices, chosenId) { station ->
          if (choosing == RideEnd.START) origin = station.id else destination = station.id
          failed = false
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { failed = !onSave(origin, destination) },
        colors = ButtonDefaults.buttonColors(containerColor = MtaColors.ActionGreen),
        modifier = Modifier.testTag("edit_stations_save"),
      ) {
        Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("edit_stations_cancel")) {
        Text("Cancel", color = MtaColors.TextSecondary)
      }
    },
    modifier = Modifier.padding(horizontal = 16.dp).testTag("edit_stations_dialog"),
  )
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
        .clip(RoundedCornerShape(10.dp))
        .background(ChoiceBg)
        .border(
          width = if (selected) 2.dp else 1.dp,
          color = if (selected) MtaColors.AccentGold else MtaColors.CardBorder,
          shape = RoundedCornerShape(10.dp),
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .testTag("edit_stations_choose_${end.name.lowercase()}"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      end.label,
      color = if (selected) MtaColors.AccentGold else MtaColors.TextSecondary,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(end = 2.dp),
    )
    Text(
      station?.name ?: stationId,
      color = MtaColors.TextPrimary,
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f),
    )
    station?.let { Bullets(it.daytimeRoutes) }
  }
}

@Composable
private fun SearchField(choosing: RideEnd, query: String, onQueryChange: (String) -> Unit) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    label = { Text("Search for the ${choosing.label.lowercase()} station", fontSize = 12.sp) },
    singleLine = true,
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedTextColor = MtaColors.TextPrimary,
        unfocusedTextColor = MtaColors.TextPrimary,
        focusedBorderColor = MtaColors.AccentGold,
        unfocusedBorderColor = MtaColors.CardBorder,
        focusedLabelColor = MtaColors.AccentGold,
        unfocusedLabelColor = MtaColors.TextSecondary,
        cursorColor = MtaColors.AccentGold,
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
      Text("No stations match.", color = MtaColors.TextSecondary, fontSize = 13.sp)
    }
    choices.forEach { (station, miles) ->
      val chosen = station.id == chosenId
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (chosen) ChoiceBg else Color.Transparent)
            .clickable { onPick(station) }
            .padding(horizontal = 8.dp, vertical = 9.dp)
            .testTag("edit_stations_option_${station.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          station.name,
          color = if (chosen) MtaColors.AccentGold else MtaColors.TextPrimary,
          fontSize = 14.sp,
          fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
          modifier = Modifier.weight(1f),
        )
        Bullets(station.daytimeRoutes)
        if (miles != null) {
          Text(distanceText(miles), color = MtaColors.TextSecondary, fontSize = 11.sp)
        }
      }
    }
  }
}

@Composable
private fun Bullets(routes: List<String>) {
  Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    bulletRoutes(routes).forEach { MtaRouteBullet(routeId = it, size = 20.dp, fontSize = 10.sp) }
  }
}
