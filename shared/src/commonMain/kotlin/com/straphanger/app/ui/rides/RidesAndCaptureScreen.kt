package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.CardTitle
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.countOf
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet

@Composable
fun RidesHistoryAndLoggerScreen(
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  onUpdateLogForm: (String, String?, String, String?, Boolean?) -> Unit,
  onSubmitQuickRide: () -> Unit,
  onSimulateAmbiguousRide: () -> Unit,
  showDeveloperTools: Boolean = false,
  onLoadDemoRides: () -> Unit,
  onConfirmAmbiguousRide: (String, String, Boolean) -> Unit,
  onKeepRideAsIs: (String) -> Unit = {},
  onDeleteRide: (String) -> Unit = {},
) {
  val pendingReviewRides = uiState.rides.filter { it.ride.needsReview }
  var rideIdToDelete by remember { mutableStateOf<String?>(null) }

  rideIdToDelete?.let { rideId ->
    DeleteRideDialog(
      onConfirm = {
        rideIdToDelete = null
        onDeleteRide(rideId)
      },
      onDismiss = { rideIdToDelete = null },
    )
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MtaColors.DarkBg)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
        .testTag("rides_history_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Rides that need review: express or local, or a station name that matched several stations
    if (pendingReviewRides.isNotEmpty()) {
      NeedsReviewBanner(pendingReviewRides, graphEngine, onConfirmAmbiguousRide, onKeepRideAsIs)
    }
    QuickTripLoggerCard(
      uiState = uiState,
      graphEngine = graphEngine,
      onUpdateLogForm = onUpdateLogForm,
      onSubmitQuickRide = onSubmitQuickRide,
      showDeveloperTools = showDeveloperTools,
      onSimulateAmbiguousRide = onSimulateAmbiguousRide,
    )
    if (showDeveloperTools) {
      Button(
        onClick = onLoadDemoRides,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF243044)),
        modifier = Modifier.testTag("load_demo_rides_btn"),
      ) {
        Text("Load sample rides", fontSize = 12.sp)
      }
    }
    CardTitle("Recorded Subway Rides (${uiState.rides.size})", fontSize = 16.sp)
    uiState.rides.forEach { rec ->
      RideHistoryItem(rec, graphEngine, onDelete = { rideIdToDelete = rec.ride.rideId })
    }
  }
}

@Composable
private fun DeleteRideDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
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

@Composable
private fun NeedsReviewBanner(
  rides: List<CompleteRideRecord>,
  graphEngine: SubwayGraphEngine,
  onConfirmAmbiguousRide: (String, String, Boolean) -> Unit,
  onKeepRideAsIs: (String) -> Unit,
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
      Text(
        text =
          "${graphEngine.stationName(rec.ride.originStationId)} → " +
            "${graphEngine.stationName(rec.ride.destinationStationId)} " +
            "(${rec.ride.totalDistanceMiles} mi). ${review.prompt}",
        color = Color.White,
        fontSize = 13.sp,
      )
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        review.actions.forEach { action ->
          ReviewActionButton(rec.ride.rideId, action, onConfirmAmbiguousRide, onKeepRideAsIs)
        }
      }
    }
  }
}

@Composable
private fun ReviewActionButton(
  rideId: String,
  action: ReviewAction,
  onConfirmAmbiguousRide: (String, String, Boolean) -> Unit,
  onKeepRideAsIs: (String) -> Unit,
) {
  when (action) {
    is ReviewAction.ConfirmCandidate ->
      Button(
        onClick = { onConfirmAmbiguousRide(rideId, action.routeId, action.isExpress) },
        colors =
          ButtonDefaults.buttonColors(
            containerColor = if (action.index == 0) MtaColors.ActionGreen else Color(0xFF2B3548)
          ),
        modifier = Modifier.testTag("confirm_candidate_${rideId}_${action.index}"),
      ) {
        Text(action.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    ReviewAction.KeepAsIs ->
      Button(
        onClick = { onKeepRideAsIs(rideId) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
        modifier = Modifier.testTag("keep_as_is_$rideId"),
      ) {
        Text("Keep as is", fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
  }
}

/** Supports origin, an optional transfer, destination, and express or local. */
@Composable
private fun QuickTripLoggerCard(
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  onUpdateLogForm: (String, String?, String, String?, Boolean?) -> Unit,
  onSubmitQuickRide: () -> Unit,
  showDeveloperTools: Boolean,
  onSimulateAmbiguousRide: () -> Unit,
) {
  SectionCard(spacing = 10.dp, modifier = Modifier.testTag("quick_trip_logger_card")) {
    CardTitle("Quick Trip Logger (Single or Multi-Leg Transfer)", fontSize = 16.sp)
    val transName =
      uiState.logTransferStationId?.let { graphEngine.getStation(it)?.name }
        ?: "None (Direct / Auto)"
    Text(
      text =
        "Origin: ${graphEngine.stationName(uiState.logOriginStationId)}  →  " +
          "Transfer: $transName  →  " +
          "Destination: ${graphEngine.stationName(uiState.logDestinationStationId)}",
      color = MtaColors.AccentGold,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = "Popular Trip Presets (or tap any station on the Map tab):",
      color = MtaColors.TextSecondary,
      fontSize = 11.sp,
    )
    TripPresets(onUpdateLogForm)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = onSubmitQuickRide,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0039A6)),
        modifier = Modifier.weight(1f).testTag("submit_log_ride_btn"),
      ) {
        Text("Log Subway Ride", fontWeight = FontWeight.Bold, fontSize = 13.sp)
      }
      if (showDeveloperTools) {
        Button(
          onClick = onSimulateAmbiguousRide,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF243044)),
          modifier = Modifier.weight(1f).testTag("simulate_passive_ride_btn"),
        ) {
          Text("Simulate Passive GPS Ride", fontSize = 12.sp)
        }
      }
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
private fun RideHistoryItem(
  rec: CompleteRideRecord,
  graphEngine: SubwayGraphEngine,
  onDelete: () -> Unit,
) {
  val needsReview = rec.ride.needsReview
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MtaColors.CardBg)
        .border(
          width = 1.dp,
          color = if (needsReview) MtaColors.AccentGold else MtaColors.CardBorder,
          shape = RoundedCornerShape(12.dp),
        )
        .padding(12.dp)
        .testTag("ride_item_${rec.ride.rideId}"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    RideLegsRow(rec)
    Text(
      text =
        "${graphEngine.stationName(rec.ride.originStationId)} → " +
          graphEngine.stationName(rec.ride.destinationStationId),
      color = Color.White,
      fontSize = 14.sp,
      fontWeight = FontWeight.SemiBold,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = rideDetail(rec),
        color = if (needsReview) MtaColors.AccentGold else MtaColors.TextSecondary,
        fontSize = 11.sp,
        modifier = Modifier.weight(1f),
      )
      TextButton(
        onClick = onDelete,
        modifier = Modifier.testTag("delete_ride_btn_${rec.ride.rideId}"),
      ) {
        Text("Delete", color = MtaColors.TextSecondary, fontSize = 12.sp)
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
          color = MtaColors.TextSecondary,
          fontSize = 11.sp,
        )
      }
    }
    Text(
      text = "${rec.ride.totalDistanceMiles} mi • ${rec.ride.durationMinutes} min",
      color = MtaColors.AccentGreen,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun PresetChip(
  label: String,
  testTag: String,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier.clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF1F293A))
        .border(1.dp, Color(0xFF33435E), RoundedCornerShape(8.dp))
        .clickable { onClick() }
        .padding(horizontal = 10.dp, vertical = 6.dp)
        .testTag(testTag)
  ) {
    Text(
      text = label,
      color = Color.White,
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
    )
  }
}
