package com.straphanger.app.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.ui.theme.Spacing
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_star_filled

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
    label = { Text("Find a station") },
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
        .heightIn(min = 48.dp)
        .clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(Spacing.md)
        .testTag("status_station_${complex.complexId}"),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (favorite) {
      Icon(
        painterResource(Res.drawable.ic_star_filled),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(end = Spacing.xs).size(18.dp),
      )
    }
    Text(
      complex.name,
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
    Bullets(complex.routes)
  }
}
