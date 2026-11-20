package com.straphanger.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.countOf
import com.straphanger.app.ui.theme.MtaColors

private val QuietButton = Color(0xFF2B3548)

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
    background = Color(0xFF281F12),
    borderColor = MtaColors.AccentGold,
  ) {
    Text(
      text = "Needs review: ${countOf(rides.size, "ride")}",
      color = MtaColors.AccentGold,
      fontSize = 15.sp,
      fontWeight = FontWeight.ExtraBold,
    )
    rides.forEach { rec ->
      val review = reviewFor(rec.ride)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = rideWhen(rec.ride.startTimeEpochMs, rec.ride.endTimeEpochMs),
          color = MtaColors.AccentGold,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.testTag("review_when_${rec.ride.rideId}"),
        )
        Text(
          text =
            "${graphEngine.stationName(rec.ride.originStationId)} → " +
              "${graphEngine.stationName(rec.ride.destinationStationId)} " +
              "(${rec.ride.totalDistanceMiles} mi). ${review.prompt}",
          color = Color.White,
          fontSize = 13.sp,
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
        QuietButton,
        "review_edit_stations_$rideId",
      ) {
        actions.onEditStations(rideId)
      }
    }
    if (next != null) {
      ReviewButton(
        "Combine with next ride (${graphEngine.stationName(rec.ride.originStationId)} → " +
          "${graphEngine.stationName(next.ride.destinationStationId)})",
        QuietButton,
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
        if (action.index == 0) MtaColors.ActionGreen else QuietButton,
        "confirm_candidate_${rideId}_${action.index}",
      ) {
        actions.onConfirmAmbiguousRide(rideId, action.routeId, action.isExpress)
      }
    ReviewAction.KeepAsIs -> {
      // The only choice when there is no line to confirm, so it is the main one.
      val onlyChoice = review.actions.none { it is ReviewAction.ConfirmCandidate }
      ReviewButton(
        "Looks right",
        if (onlyChoice) MtaColors.ActionGreen else QuietButton,
        "keep_as_is_$rideId",
      ) {
        actions.onKeepRideAsIs(rideId)
      }
    }
  }
}

@Composable
private fun ReviewButton(label: String, color: Color, tag: String, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    colors = ButtonDefaults.buttonColors(containerColor = color),
    modifier = Modifier.testTag(tag),
  ) {
    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
  }
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
    containerColor = MtaColors.CardBg,
    titleContentColor = MtaColors.TextPrimary,
    textContentColor = MtaColors.TextPrimary,
    title = { Text("Combine into one ride?", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "${graphEngine.stationName(first.ride.originStationId)} → " +
            graphEngine.stationName(second.ride.destinationStationId),
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.testTag("combine_trip"),
        )
        Text("First: ${describe(first)}", fontSize = 13.sp)
        Text("Then: ${describe(second)}", fontSize = 13.sp)
        Text(
          "The lines are found again for the whole trip, and the second ride is removed.",
          color = MtaColors.TextSecondary,
          fontSize = 12.sp,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        colors = ButtonDefaults.buttonColors(containerColor = MtaColors.ActionGreen),
        modifier = Modifier.testTag("confirm_combine_btn"),
      ) {
        Text("Combine", color = Color.White, fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_combine_btn")) {
        Text("Cancel", color = MtaColors.TextSecondary)
      }
    },
    modifier = Modifier.testTag("combine_rides_dialog"),
  )
}
