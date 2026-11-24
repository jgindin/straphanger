package com.straphanger.app.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.hasWindow
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.status.commuteRowText
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.StraphangerTheme
import com.straphanger.app.ui.theme.primaryButtonColors

/**
 * A widget's configuration screen: every one of [commutes] with a checkbox, starting from
 * [initiallySelected]. Only commutes with an end time can be ticked. Done passes the ticked ids, in
 * the order of [commutes], to [onDone]. Without any commutes it offers [onOpenApp] instead, and
 * also when none of them has an end time. [complexName] names a commute's station, or is null for
 * one no longer listed. [use24Hour] picks "7:45" or "7:45 AM" for the commutes' times.
 */
@Composable
fun WidgetCommutePicker(
  commutes: List<Commute>,
  complexName: (Commute) -> String?,
  initiallySelected: Set<String>,
  onDone: (List<String>) -> Unit,
  onOpenApp: () -> Unit,
  use24Hour: Boolean,
) {
  var selected by remember(initiallySelected) { mutableStateOf(initiallySelected) }
  StraphangerTheme {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .verticalScroll(rememberScrollState())
          .padding(Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      Text(
        "Choose commutes",
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
      )
      if (commutes.isEmpty()) {
        Text(
          "No commutes yet. Open Straphanger to add one.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
        )
        OpenAppButton(onOpenApp)
      } else {
        SectionCard(spacing = 4.dp) {
          commutes.forEach { commute ->
            PickerRow(commute, complexName(commute), use24Hour, commute.id in selected) { ticked ->
              selected = if (ticked) selected + commute.id else selected - commute.id
            }
          }
        }
        Button(
          onClick = { onDone(commutes.filter { it.hasWindow && it.id in selected }.map { it.id }) },
          colors = primaryButtonColors(),
          modifier = Modifier.align(Alignment.End).testTag("widget_done"),
        ) {
          Text("Done")
        }
        // With no commute that can be ticked, the way to set an end time is in the app.
        if (commutes.none { it.hasWindow }) OpenAppButton(onOpenApp)
      }
    }
  }
}

@Composable
private fun OpenAppButton(onOpenApp: () -> Unit) {
  Button(
    onClick = onOpenApp,
    colors = primaryButtonColors(),
    modifier = Modifier.testTag("widget_open_app"),
  ) {
    Text("Open Straphanger")
  }
}

/** One commute, ticked or not; greyed out with a hint when it has no end time. */
@Composable
private fun PickerRow(
  commute: Commute,
  stationName: String?,
  use24Hour: Boolean,
  ticked: Boolean,
  onTicked: (Boolean) -> Unit,
) {
  val usable = commute.hasWindow
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .toggleable(
          value = ticked && usable,
          enabled = usable,
          role = Role.Checkbox,
          onValueChange = onTicked,
        )
        .heightIn(min = 48.dp)
        .padding(vertical = Spacing.xs)
        .testTag("widget_commute_${commute.id}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = ticked && usable, onCheckedChange = null, enabled = usable)
    Column(modifier = Modifier.padding(start = 8.dp)) {
      Text(
        commuteRowText(commute, stationName, use24Hour),
        color =
          if (usable) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      if (!usable) {
        Text(
          "Set an end time to show it on a widget",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}
