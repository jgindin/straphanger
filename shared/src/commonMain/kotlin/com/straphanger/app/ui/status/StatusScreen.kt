package com.straphanger.app.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.commute.minutesText
import com.straphanger.app.data.commute.stationComplexes
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.local.FavoriteStationsStore
import com.straphanger.app.data.local.InMemoryFavoriteStationsStore
import com.straphanger.app.data.realtime.AlertKind
import com.straphanger.app.data.realtime.Arrival
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.data.realtime.ServiceAlert
import com.straphanger.app.ui.CardTitle
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import com.straphanger.app.ui.theme.tabular
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_star
import straphanger.shared.generated.resources.ic_star_filled

private const val STALE_AFTER_SECONDS = 90L

/**
 * Live arrivals at a station and the MTA's current service alerts. [fetch] reads the feeds; it is
 * called when the screen opens, when a station is picked, every 30 seconds while the screen is
 * showing, and on "Refresh". A [tap] ("See trains") is applied once, then reported through
 * [onTapConsumed], so a rider's "Show all" sticks. [nowEpochSeconds] is the clock.
 */
@Composable
internal fun StatusScreen(
  network: SubwayNetworkData,
  fetch: suspend (Collection<String>) -> RealtimeSnapshot,
  nowEpochSeconds: () -> Long,
  favorites: FavoriteStationsStore = InMemoryFavoriteStationsStore(),
  tap: CommuteTap? = null,
  onTapConsumed: () -> Unit = {},
  commutes: CommuteControls = CommuteControls(),
) {
  val complexes = remember(network) { stationComplexes(network) }
  var query by rememberSaveable { mutableStateOf("") }
  var favoriteIds by remember { mutableStateOf(favorites.read()) }
  var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
  var filter by rememberSaveable(stateSaver = StatusFilter.Saver) { mutableStateOf(null) }
  val selected = complexes.firstOrNull { it.complexId == selectedId }
  var refreshRequests by remember { mutableLongStateOf(0) }
  var state by remember { mutableStateOf(StatusState()) }
  // The request a pull started; the spinner ends when that fetch (not a periodic one) is done.
  var pulledAt by remember { mutableStateOf<Long?>(null) }
  val commuteUi = rememberCommuteUi(commutes, complexes)
  val now = rememberClock(nowEpochSeconds)
  TapEffect(tap, onTapConsumed) {
    selectedId = it.complexId
    filter = StatusFilter(it.routeId.removeSuffix("X"), it.direction)
    query = ""
  }
  RefreshFeedEffect(selected, refreshRequests, fetch, nowEpochSeconds, { state }) {
    result,
    forRequest ->
    state = result
    if (pulledAt?.let { forRequest >= it } == true) pulledAt = null
  }
  PullToRefreshList(
    pulledAt != null,
    onRefresh = { pulledAt = ++refreshRequests },
  ) {
    item { Spacer(Modifier.height(6.dp)) }
    commutesSection(commuteUi)
    stationList(
      complexes,
      favoriteIds,
      selected,
      query,
      onQueryChange = { query = it },
      onPick = { id ->
        selectedId = id
        filter = null
      },
    ) { complex ->
      ArrivalsCard(
        complex,
        state,
        now,
        complex.complexId in favoriteIds,
        filter,
        { filter = null },
        { commuteUi.add(complex, filter) },
      ) {
        favoriteIds = toggleFavorite(favoriteIds, complex.complexId).also(favorites::write)
      }
    }
    item { FreshnessLine(state, now) { refreshRequests++ } }
    item { AlertsSection(state.snapshot?.alerts.orEmpty(), selected?.routes.orEmpty()) }
    item { Spacer(Modifier.height(6.dp)) }
  }
  commuteUi.editor?.let { CommuteEditorDialog(it, commuteUi) }
}

/** The Status list, which a pull down on refreshes; [refreshing] shows the spinner. */
@OptIn(ExperimentalMaterial3Api::class) // PullToRefreshBox is still experimental
@Composable
private fun PullToRefreshList(
  refreshing: Boolean,
  onRefresh: () -> Unit,
  content: LazyListScope.() -> Unit,
) {
  PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = onRefresh,
    modifier = Modifier.fillMaxSize().testTag("status_pull_refresh"),
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("status_screen"),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
    )
  }
}

@Composable
internal fun Bullets(routes: List<String>) {
  Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    routes.forEach { MtaRouteBullet(routeId = it, size = 20.dp, fontSize = 10.sp) }
  }
}

@Composable
private fun ArrivalsCard(
  complex: StationComplex,
  state: StatusState,
  now: Long,
  isFavorite: Boolean,
  filter: StatusFilter?,
  onClearFilter: () -> Unit,
  onAddCommute: () -> Unit,
  onToggleFavorite: () -> Unit,
) {
  SectionCard(spacing = 8.dp, modifier = Modifier.testTag("status_arrivals")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        complex.name,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      FavoriteToggle(isFavorite, onToggleFavorite)
    }
    if (filter != null) FilterChip(filter, onClearFilter)
    AddCommuteButton(onAddCommute)
    val board = state.snapshot?.arrivals
    val arrivals =
      complex.stationIds
        .flatMap { board?.arrivalsAt(it, now).orEmpty() }
        .filter { filter == null || filter.matches(it) }
        .sortedBy { it.epochSeconds }
    if (board == null) {
      SecondaryText("Loading arrivals…")
    } else if (arrivals.isEmpty()) {
      SecondaryText("No trains listed right now.")
    }
    Direction.entries.forEach { direction ->
      val here = arrivals.filter { it.direction == direction }
      if (here.isNotEmpty()) DirectionBlock(direction, here, now)
    }
  }
}

@Composable
private fun FavoriteToggle(isFavorite: Boolean, onToggle: () -> Unit) {
  IconToggleButton(
    checked = isFavorite,
    onCheckedChange = { onToggle() },
    modifier = Modifier.testTag("status_favorite_toggle"),
  ) {
    Icon(
      painterResource(if (isFavorite) Res.drawable.ic_star_filled else Res.drawable.ic_star),
      contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
      tint =
        if (isFavorite) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun FilterChip(filter: StatusFilter, onClear: () -> Unit) {
  TextButton(onClick = onClear, modifier = Modifier.testTag("status_filter_clear")) {
    Text("${filter.label} · Show all")
  }
}

@Composable
private fun SecondaryText(text: String) {
  Text(
    text,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
}

@Composable
private fun DirectionBlock(direction: Direction, arrivals: List<Arrival>, now: Long) {
  Text(
    directionText(direction),
    color = MaterialTheme.colorScheme.tertiary,
    style = MaterialTheme.typography.labelLarge,
  )
  arrivals.take(MAX_ROWS_PER_DIRECTION).forEach { arrival ->
    Row(
      modifier = Modifier.fillMaxWidth().testTag("status_arrival_${arrival.routeId}"),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      MtaRouteBullet(routeId = arrival.routeId, size = 22.dp, fontSize = 11.sp)
      Text(
        minutesText(arrival.minutesFrom(now)),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium.tabular(),
      )
    }
  }
}

private const val MAX_ROWS_PER_DIRECTION = 6

@Composable
private fun FreshnessLine(state: StatusState, now: Long, onRefresh: () -> Unit) {
  val snapshot = state.snapshot
  val failed = state.failedAtEpochSeconds
  val message =
    when {
      snapshot == null && failed != null ->
        "Can't reach the MTA's feeds. Check your connection and try again."
      failed != null ->
        "Couldn't refresh. Showing data from ${ageText(now - snapshot!!.fetchedAtEpochSeconds)} ago."
      snapshot == null -> "Loading…"
      snapshot.partial -> "Some lines' feeds didn't load, so this may be incomplete."
      now - snapshot.fetchedAtEpochSeconds > STALE_AFTER_SECONDS ->
        "Updated ${ageText(now - snapshot.fetchedAtEpochSeconds)} ago."
      else -> "Up to date."
    }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      message,
      color = if (failed != null) MtaColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall.tabular(),
      modifier = Modifier.weight(1f).testTag("status_freshness"),
    )
    TextButton(onClick = onRefresh, modifier = Modifier.testTag("status_refresh")) {
      Text("Refresh")
    }
  }
}

@Composable
private fun AlertsSection(alerts: List<ServiceAlert>, myRoutes: List<String>) {
  var showPlanned by rememberSaveable { mutableStateOf(false) }
  val (planned, now) = alerts.partition { it.kind == AlertKind.PLANNED }
  // Alerts for the lines at the chosen station come first.
  val current = now.sortedBy { alert -> if (alert.routes.any { it in myRoutes }) 0 else 1 }
  SectionCard(spacing = 8.dp, modifier = Modifier.testTag("status_alerts")) {
    CardTitle("Service alerts")
    if (current.isEmpty()) SecondaryText("No current delays or changes.")
    current.forEach { AlertRow(it) }
    if (planned.isNotEmpty()) {
      TextButton(
        onClick = { showPlanned = !showPlanned },
        modifier = Modifier.testTag("status_planned_toggle"),
      ) {
        Text(if (showPlanned) "Hide planned work" else "Show planned work (${planned.size})")
      }
      if (showPlanned) planned.forEach { AlertRow(it) }
    }
  }
}

@Composable
private fun AlertRow(alert: ServiceAlert) {
  Column(
    modifier = Modifier.fillMaxWidth().testTag("status_alert"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Bullets(alert.routes.sorted())
    Text(
      alert.header,
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}
