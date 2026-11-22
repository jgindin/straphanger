package com.straphanger.app.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import kotlinx.coroutines.delay

private const val REFRESH_MS = 30_000L
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
) {
  val complexes = remember(network) { stationComplexes(network) }
  var query by rememberSaveable { mutableStateOf("") }
  var favoriteIds by remember { mutableStateOf(favorites.read()) }
  var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
  var filter by rememberSaveable(stateSaver = StatusFilter.Saver) { mutableStateOf(null) }
  val selected = complexes.firstOrNull { it.complexId == selectedId }
  var refreshRequests by remember { mutableLongStateOf(0) }
  var state by remember { mutableStateOf(StatusState()) }
  val now = rememberClock(nowEpochSeconds)
  LaunchedEffect(tap) {
    if (tap != null) {
      selectedId = tap.complexId
      filter = StatusFilter(tap.routeId.removeSuffix("X"), tap.direction)
      query = ""
      onTapConsumed()
    }
  }
  LaunchedEffect(selected, refreshRequests) {
    while (true) {
      state = refreshStatus(state, selected?.routes.orEmpty(), fetch, nowEpochSeconds())
      delay(REFRESH_MS)
    }
  }
  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("status_screen"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item { Spacer(Modifier.height(6.dp)) }
    item { StationSearch(query, { query = it }) }
    stationRows(complexes, favoriteIds, selectedId, query) { id ->
      selectedId = id
      filter = null
      query = ""
    }
    if (selected != null) {
      item {
        ArrivalsCard(
          selected,
          state,
          now,
          selected.complexId in favoriteIds,
          filter,
          { filter = null },
        ) {
          favoriteIds = toggleFavorite(favoriteIds, selected.complexId)
          favorites.write(favoriteIds)
        }
      }
    }
    item { FreshnessLine(state, now) { refreshRequests++ } }
    item { AlertsSection(state.snapshot?.alerts.orEmpty(), selected?.routes.orEmpty()) }
    item { Spacer(Modifier.height(6.dp)) }
  }
}

private fun LazyListScope.stationRows(
  complexes: List<StationComplex>,
  favoriteIds: List<String>,
  selectedId: String?,
  query: String,
  onPick: (String) -> Unit,
) {
  if (query.isBlank()) {
    items(favoriteIds.mapNotNull { id -> complexes.firstOrNull { it.complexId == id } }) { complex
      ->
      if (complex.complexId != selectedId) {
        StationRow(complex, favorite = true) { onPick(complex.complexId) }
      }
    }
  }
  items(searchComplexes(complexes, query).filter { it.complexId != selectedId }) { complex ->
    StationRow(complex) { onPick(complex.complexId) }
  }
}

@Composable
private fun StationSearch(query: String, onQueryChange: (String) -> Unit) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    label = { Text("Find a station", fontSize = 12.sp) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().testTag("status_search"),
  )
}

@Composable
private fun StationRow(
  complex: StationComplex,
  favorite: Boolean = false,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(MtaColors.CardBg)
        .clickable(onClick = onClick)
        .padding(12.dp)
        .testTag("status_station_${complex.complexId}"),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    if (favorite) Text("★ ", color = MtaColors.AccentGold, fontSize = 14.sp)
    Text(complex.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
    Bullets(complex.routes)
  }
}

@Composable
private fun Bullets(routes: List<String>) {
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
  onToggleFavorite: () -> Unit,
) {
  SectionCard(spacing = 8.dp, modifier = Modifier.testTag("status_arrivals")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        complex.name,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
      )
      Text(
        if (isFavorite) "★ Favorite" else "☆ Favorite",
        color = MtaColors.AccentGold,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier =
          Modifier.clickable(onClick = onToggleFavorite)
            .padding(4.dp)
            .testTag("status_favorite_toggle"),
      )
    }
    if (filter != null) FilterChip(filter, onClearFilter)
    val board = state.snapshot?.arrivals
    val arrivals =
      complex.stationIds
        .flatMap { board?.arrivalsAt(it, now).orEmpty() }
        .filter { filter == null || filter.matches(it) }
        .sortedBy { it.epochSeconds }
    if (board == null) {
      Text("Loading arrivals…", color = MtaColors.TextSecondary, fontSize = 12.sp)
    } else if (arrivals.isEmpty()) {
      Text("No trains listed right now.", color = MtaColors.TextSecondary, fontSize = 12.sp)
    }
    Direction.entries.forEach { direction ->
      val here = arrivals.filter { it.direction == direction }
      if (here.isNotEmpty()) DirectionBlock(direction, here, now)
    }
  }
}

@Composable
private fun FilterChip(filter: StatusFilter, onClear: () -> Unit) {
  Text(
    "${filter.label} · Show all",
    color = MtaColors.AccentGold,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.clickable(onClick = onClear).padding(4.dp).testTag("status_filter_clear"),
  )
}

@Composable
private fun DirectionBlock(direction: Direction, arrivals: List<Arrival>, now: Long) {
  Text(
    if (direction == Direction.NORTH) "Northbound" else "Southbound",
    color = MtaColors.AccentGold,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
  )
  arrivals.take(MAX_ROWS_PER_DIRECTION).forEach { arrival ->
    Row(
      modifier = Modifier.fillMaxWidth().testTag("status_arrival_${arrival.routeId}"),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      MtaRouteBullet(routeId = arrival.routeId, size = 22.dp, fontSize = 11.sp)
      Text(minutesText(arrival.minutesFrom(now)), color = Color.White, fontSize = 14.sp)
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
  ) {
    Text(
      message,
      color = if (failed != null) MtaColors.AccentGold else MtaColors.TextSecondary,
      fontSize = 12.sp,
      modifier = Modifier.weight(1f).testTag("status_freshness"),
    )
    Text(
      "Refresh",
      color = Color.White,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.clickable(onClick = onRefresh).padding(4.dp).testTag("status_refresh"),
    )
  }
}

@Composable
private fun AlertsSection(alerts: List<ServiceAlert>, myRoutes: List<String>) {
  var showPlanned by rememberSaveable { mutableStateOf(false) }
  val (planned, now) = alerts.partition { it.kind == AlertKind.PLANNED }
  // Alerts for the lines at the chosen station come first.
  val current = now.sortedBy { alert -> if (alert.routes.any { it in myRoutes }) 0 else 1 }
  SectionCard(spacing = 8.dp, modifier = Modifier.testTag("status_alerts")) {
    Text("Service alerts", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    if (current.isEmpty()) {
      Text("No current delays or changes.", color = MtaColors.TextSecondary, fontSize = 12.sp)
    }
    current.forEach { AlertRow(it) }
    if (planned.isNotEmpty()) {
      Text(
        if (showPlanned) "Hide planned work" else "Show planned work (${planned.size})",
        color = MtaColors.AccentGold,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier =
          Modifier.clickable { showPlanned = !showPlanned }.testTag("status_planned_toggle"),
      )
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
    Text(alert.header, color = Color.White, fontSize = 12.sp)
  }
}
