package com.straphanger.app.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.ui.theme.MtaColors

/**
 * The station search, then the favorites (when not searching), then [selected]'s card when it is
 * not one of them, then the search results. A favorite that is [selected] shows as its [card] in
 * its own place, so it opens where it was tapped instead of jumping below the others. Picking a
 * station clears the search.
 */
internal fun LazyListScope.stationList(
  complexes: List<StationComplex>,
  favoriteIds: List<String>,
  selected: StationComplex?,
  query: String,
  onQueryChange: (String) -> Unit,
  onPick: (String) -> Unit,
  card: @Composable (StationComplex) -> Unit,
) {
  item { StationSearch(query, onQueryChange) }
  val pick = { id: String ->
    onPick(id)
    onQueryChange("")
  }
  val shownFavorites =
    if (query.isBlank())
      favoriteIds.mapNotNull { id -> complexes.firstOrNull { it.complexId == id } }
    else emptyList()
  items(shownFavorites, key = { "favorite_${it.complexId}" }) { complex ->
    if (complex.complexId == selected?.complexId) {
      card(complex)
    } else {
      StationRow(complex, favorite = true) { pick(complex.complexId) }
    }
  }
  if (selected != null && selected !in shownFavorites) {
    item(key = "selected_${selected.complexId}") { card(selected) }
  }
  items(
    searchComplexes(complexes, query).filter { it.complexId != selected?.complexId },
    key = { "result_${it.complexId}" },
  ) { complex ->
    StationRow(complex) { pick(complex.complexId) }
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
