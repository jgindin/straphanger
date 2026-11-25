package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.tabular

/**
 * One recorded ride: when, which lines, where from and to. A ride that needs review offers its
 * review choices here too, and any ride the next one could continue offers to combine them, above
 * Edit (the station editor) and Delete. Delete (also a screen reader's "Delete ride" action)
 * deletes at once; the app offers to undo it.
 */
@Composable
internal fun RideHistoryItem(
  rec: CompleteRideRecord,
  graphEngine: SubwayGraphEngine,
  next: CompleteRideRecord?,
  actions: RideReviewActions,
  onDelete: () -> Unit,
) {
  val needsReview = rec.ride.needsReview
  var expanded by rememberSaveable(rec.ride.rideId) { mutableStateOf(false) }
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(
          width = 1.dp,
          color = if (needsReview) MtaColors.Warning else MaterialTheme.colorScheme.outlineVariant,
          shape = MaterialTheme.shapes.medium,
        )
        .clickable(role = Role.Button) { expanded = !expanded }
        // Swiping deletes too; this is the same delete for a screen reader.
        .semantics {
          customActions =
            listOf(
              CustomAccessibilityAction("Delete ride") {
                onDelete()
                true
              },
            )
        }
        .padding(Spacing.md)
        .testTag("ride_item_${rec.ride.rideId}"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = rideWhen(rec.ride.startTimeEpochMs, rec.ride.endTimeEpochMs),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.testTag("ride_when_${rec.ride.rideId}"),
    )
    RideLegsRow(rec)
    Text(
      text =
        "${graphEngine.stationName(rec.ride.originStationId)} → " +
          graphEngine.stationName(rec.ride.destinationStationId),
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.titleSmall,
    )
    if (expanded) RideLegBreakdown(rec, graphEngine)
    if (needsReview || next != null) {
      val review =
        if (needsReview) reviewFor(rec.ride, rec.legs.size) { graphEngine.stationName(it) }
        else null
      ReviewButtons(rec, review, next, graphEngine, actions)
    }
    RideDetailRow(rec, onEdit = { actions.onEditStations(rec.ride.rideId) }, onDelete = onDelete)
  }
}

/** Where the ride came from and its stops, with Edit and Delete. */
@Composable
private fun RideDetailRow(rec: CompleteRideRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = rideDetail(rec),
      color =
        if (rec.ride.needsReview) MtaColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onEdit, modifier = Modifier.testTag("edit_ride_btn_${rec.ride.rideId}")) {
      Text("Edit")
    }
    TextButton(
      onClick = onDelete,
      modifier = Modifier.testTag("delete_ride_btn_${rec.ride.rideId}"),
    ) {
      Text("Delete")
    }
  }
}

/** One line per leg, shown when the card is tapped. */
@Composable
private fun RideLegBreakdown(rec: CompleteRideRecord, graphEngine: SubwayGraphEngine) {
  Column(
    modifier = Modifier.fillMaxWidth().testTag("ride_breakdown_${rec.ride.rideId}"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    rec.legs.forEach { leg ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        MtaRouteBullet(routeId = leg.routeId, size = 20.dp, fontSize = 10.sp)
        Text(
          legDetail(leg, graphEngine),
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

/** Each leg's route bullet with express or local, and the ride's distance and duration. */
@Composable
private fun RideLegsRow(rec: CompleteRideRecord) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      rec.legs.forEach { leg ->
        MtaRouteBullet(routeId = leg.routeId, size = 24.dp, fontSize = 11.sp)
        Text(
          text = if (leg.isExpress) "Express" else "Local",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    Text(
      text = "${rec.ride.totalDistanceMiles} mi • ${rec.ride.durationMinutes} min",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelLarge.tabular(),
    )
  }
}
