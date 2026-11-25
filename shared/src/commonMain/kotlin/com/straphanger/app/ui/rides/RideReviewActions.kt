package com.straphanger.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.countOf
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.confirmButtonColors
import com.straphanger.app.ui.theme.quietButtonColors

/**
 * What a ride's review buttons do, wherever they are shown: confirm a line, say the ride looks
 * right, edit its stations (the banner's "Edit stations" and a row's "Edit"), or ask to combine it
 * with the ride after it.
 */
internal class RideReviewActions(
  val onConfirmAmbiguousRide: (String, String, Boolean) -> Unit,
  val onKeepRideAsIs: (String) -> Unit,
  val onEditStations: (String) -> Unit,
  /** Asks whether to combine the first ride with the next; nothing changes until confirmed. */
  val onCombine: (CompleteRideRecord, CompleteRideRecord) -> Unit,
)

/** Every ride that needs review, each with when it happened, what to check, and its buttons. */
@Composable
internal fun NeedsReviewBanner(
  rides: List<CompleteRideRecord>,
  graphEngine: SubwayGraphEngine,
  nextRides: Map<String, CompleteRideRecord?>,
  actions: RideReviewActions,
) {
  SectionCard(
    spacing = 8.dp,
    modifier = Modifier.testTag("needs_review_banner"),
    background = MtaColors.WarningContainer,
    borderColor = MtaColors.Warning,
  ) {
    Text(
      text = "Needs review: ${countOf(rides.size, "ride")}",
      color = MtaColors.Warning,
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
    )
    rides.forEach { rec ->
      val review = reviewFor(rec.ride)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = rideWhen(rec.ride.startTimeEpochMs, rec.ride.endTimeEpochMs),
          color = MtaColors.Warning,
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.testTag("review_when_${rec.ride.rideId}"),
        )
        Text(
          text =
            "${graphEngine.stationName(rec.ride.originStationId)} → " +
              "${graphEngine.stationName(rec.ride.destinationStationId)} " +
              "(${rec.ride.totalDistanceMiles} mi). ${review.prompt}",
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodyMedium,
        )
        ReviewButtons(
          rec = rec,
          review = review,
          next = nextRides[rec.ride.rideId],
          graphEngine = graphEngine,
          actions = actions,
          offerEditStations = true,
        )
      }
    }
  }
}

/**
 * The buttons for [rec], wrapping onto more lines as needed so none is cut off: [review]'s choices
 * when there is one, "Edit stations" when [offerEditStations], and combining with [next] when there
 * is a ride to combine with.
 */
@Composable
internal fun ReviewButtons(
  rec: CompleteRideRecord,
  review: RideReview?,
  next: CompleteRideRecord?,
  graphEngine: SubwayGraphEngine,
  actions: RideReviewActions,
  offerEditStations: Boolean = false,
) {
  val rideId = rec.ride.rideId
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    review?.actions?.forEach { action -> ReviewActionButton(rideId, action, review, actions) }
    if (offerEditStations) {
      ReviewButton(
        "Edit stations",
        quietButtonColors(),
        "review_edit_stations_$rideId",
      ) {
        actions.onEditStations(rideId)
      }
    }
    if (next != null) {
      ReviewButton(
        "Combine with next ride (${graphEngine.stationName(rec.ride.originStationId)} → " +
          "${graphEngine.stationName(next.ride.destinationStationId)})",
        quietButtonColors(),
        "combine_next_$rideId",
      ) {
        actions.onCombine(rec, next)
      }
    }
  }
}

@Composable
private fun ReviewActionButton(
  rideId: String,
  action: ReviewAction,
  review: RideReview,
  actions: RideReviewActions,
) {
  when (action) {
    is ReviewAction.ConfirmCandidate ->
      ReviewButton(
        action.label,
        if (action.index == 0) confirmButtonColors() else quietButtonColors(),
        "confirm_candidate_${rideId}_${action.index}",
      ) {
        actions.onConfirmAmbiguousRide(rideId, action.routeId, action.isExpress)
      }
    ReviewAction.KeepAsIs -> {
      // The only choice when there is no line to confirm, so it is the main one.
      val onlyChoice = review.actions.none { it is ReviewAction.ConfirmCandidate }
      ReviewButton(
        "Looks right",
        if (onlyChoice) confirmButtonColors() else quietButtonColors(),
        "keep_as_is_$rideId",
      ) {
        actions.onKeepRideAsIs(rideId)
      }
    }
  }
}

@Composable
private fun ReviewButton(label: String, colors: ButtonColors, tag: String, onClick: () -> Unit) {
  Button(onClick = onClick, colors = colors, modifier = Modifier.testTag(tag)) { Text(label) }
}

/** Asks before making [first] and [second] one ride, naming the trip and both rides' times. */
@Composable
internal fun CombineRidesDialog(
  first: CompleteRideRecord,
  second: CompleteRideRecord,
  graphEngine: SubwayGraphEngine,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  fun describe(rec: CompleteRideRecord) =
    "${graphEngine.stationName(rec.ride.originStationId)} → " +
      "${graphEngine.stationName(rec.ride.destinationStationId)}, " +
      rideWhen(rec.ride.startTimeEpochMs, rec.ride.endTimeEpochMs)
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    textContentColor = MaterialTheme.colorScheme.onSurface,
    title = { Text("Combine into one ride?", style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
          "${graphEngine.stationName(first.ride.originStationId)} → " +
            graphEngine.stationName(second.ride.destinationStationId),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.testTag("combine_trip"),
        )
        Text("First: ${describe(first)}", style = MaterialTheme.typography.bodyMedium)
        Text("Then: ${describe(second)}", style = MaterialTheme.typography.bodyMedium)
        Text(
          "The lines are found again for the whole trip, and the second ride is removed.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        colors = confirmButtonColors(),
        modifier = Modifier.testTag("confirm_combine_btn"),
      ) {
        Text("Combine")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_combine_btn")) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("combine_rides_dialog"),
  )
}
