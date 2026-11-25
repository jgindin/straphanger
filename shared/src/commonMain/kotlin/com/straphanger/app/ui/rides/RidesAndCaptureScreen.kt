package com.straphanger.app.ui.rides

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RouteChoice
import com.straphanger.app.data.local.nextRideToCombine
import com.straphanger.app.ui.StraphangerUiState
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Rides tab: the rides by day, newest first, with the review banner on top, and the log sheet
 * while [StraphangerUiState.isLogRideSheetOpen]. The "Log ride" button is the app scaffold's;
 * [onListAtTopChange] says whether the list is scrolled to the top, where the button shows its
 * text.
 *
 * @param onDeleteRide deletes the ride at once and the app offers to undo it; false when the
 *   deletion could not be saved, and the ride stays.
 * @param now the time the day headings ("Today", "Yesterday") are relative to.
 */
@Composable
fun RidesHistoryAndLoggerScreen(
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  onUpdateLogForm: (String?, String?, String?, String?, Boolean?) -> Unit,
  onSubmitQuickRide: () -> Unit,
  onConfirmAmbiguousRide: (String, String, Boolean) -> Unit,
  onKeepRideAsIs: (String) -> Unit = {},
  onDeleteRide: (CompleteRideRecord) -> Boolean = { false },
  onChangeRideStations: (String, String, String, RouteChoice?) -> Boolean = { _, _, _, _ -> false },
  onCombineRides: (String, String) -> Boolean = { _, _ -> false },
  onClearRouteFilter: () -> Unit = {},
  onCloseLogRideSheet: () -> Unit = {},
  onListAtTopChange: (Boolean) -> Unit = {},
  now: () -> Instant = { Clock.System.now() },
) {
  val pendingReviewRides = uiState.rides.filter { it.ride.needsReview }
  val nextRides = remember(uiState.rides, graphEngine) { nextRidesOf(uiState.rides, graphEngine) }
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
  if (uiState.isLogRideSheetOpen) {
    LogRideSheetHost(uiState, graphEngine, onUpdateLogForm, onSubmitQuickRide, onCloseLogRideSheet)
  }

  val listState = rememberLazyListState()
  ReportsListAtTop(listState, onListAtTopChange)
  val zone = remember { TimeZone.currentSystemDefault() }
  val today = rememberToday(now, zone)
  val rows =
    remember(uiState.rides, uiState.ridesRouteFilter, today) {
      rideListRows(
        allRides = uiState.rides,
        hasRidesToReview = pendingReviewRides.isNotEmpty(),
        routeFilter = uiState.ridesRouteFilter,
        today = today,
        zone = zone,
      )
    }
  RideList(
    rows,
    listState,
    RideListContent(
      graphEngine = graphEngine,
      pendingReviewRides = pendingReviewRides,
      nextRides = nextRides,
      reviewActions = reviewActions,
      highlightedRideId = uiState.highlightedRideId,
      onClearRouteFilter = onClearRouteFilter,
      onDelete = onDeleteRide,
    ),
  )
}

/** The log sheet, with the rider's own trips worked out from their rides. */
@Composable
private fun LogRideSheetHost(
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  onUpdateLogForm: (String?, String?, String?, String?, Boolean?) -> Unit,
  onSubmit: () -> Unit,
  onDismiss: () -> Unit,
) {
  val suggestions =
    remember(uiState.rides, graphEngine) {
      suggestedTrips(uiState.rides, stationName = { graphEngine.getStation(it)?.name })
    }
  LogRideSheet(uiState, graphEngine, suggestions, onUpdateLogForm, onSubmit, onDismiss)
}

private const val TODAY_TICK_MS = 60_000L

/** Today's date in [zone], checked every minute so "Today" moves on at midnight. */
@Composable
private fun rememberToday(now: () -> Instant, zone: TimeZone): LocalDate {
  val currentNow by rememberUpdatedState(now)
  var today by remember { mutableStateOf(now().toLocalDateTime(zone).date) }
  LaunchedEffect(zone) {
    while (true) {
      today = currentNow().toLocalDateTime(zone).date
      delay(TODAY_TICK_MS)
    }
  }
  return today
}

/** Tells [onChange] whether [listState] is at its first row, now and whenever that changes. */
@Composable
private fun ReportsListAtTop(listState: LazyListState, onChange: (Boolean) -> Unit) {
  LaunchedEffect(listState) {
    snapshotFlow { listState.firstVisibleItemIndex == 0 }.collect { onChange(it) }
  }
}

/** The ride each ride could be combined with, by ride id; null where there is none. */
private fun nextRidesOf(
  rides: List<CompleteRideRecord>,
  graphEngine: SubwayGraphEngine,
): Map<String, CompleteRideRecord?> = rides.associate {
  it.ride.rideId to nextRideToCombine(rides, it.ride, graphEngine)
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
