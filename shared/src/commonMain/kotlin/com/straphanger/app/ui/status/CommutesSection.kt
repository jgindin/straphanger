package com.straphanger.app.ui.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.commuteTimeText
import com.straphanger.app.data.commute.daysText
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.ui.CardTitle
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.theme.Spacing

/** "Northbound" or "Southbound". */
internal fun directionText(direction: Direction): String =
  if (direction == Direction.NORTH) "Northbound" else "Southbound"

/**
 * "A Southbound · 168 St · Weekdays 7:45", or "Weekdays 7:45 AM" unless [use24Hour]; [stationName]
 * is null for a station that is gone.
 */
internal fun commuteRowText(commute: Commute, stationName: String?, use24Hour: Boolean): String =
  "${commute.routeId} ${directionText(commute.direction)} · " +
    "${stationName ?: "Station no longer listed"} · " +
    "${daysText(commute.days)} ${commuteTimeText(commute.hour, commute.minute, use24Hour)}" +
    endTimeSuffix(commute, use24Hour)

private fun endTimeSuffix(commute: Commute, use24Hour: Boolean): String {
  val hour = commute.endHour
  val minute = commute.endMinute
  return if (hour != null && minute != null) "–${commuteTimeText(hour, minute, use24Hour)}" else ""
}

/** The Commutes card at the top of the Status tab; nothing until there is a commute. */
internal fun LazyListScope.commutesSection(ui: CommuteUi) {
  if (ui.commutes.isEmpty()) return
  item {
    SectionCard(spacing = 8.dp, modifier = Modifier.testTag("status_commutes")) {
      CardTitle("Commutes")
      ui.commutes.forEach { CommuteRow(it, ui) }
      val access = ui.controls.access
      if (!access.notificationsAllowed) {
        CommuteNote(
          "Notifications are blocked for Straphanger. Tap to allow them in system settings.",
          "commute_notifications_blocked",
          ui.controls.onOpenNotificationSettings,
        )
      }
      if (!access.exactAlarmsAllowed) {
        CommuteNote(
          "Alerts may be a few minutes late. Tap to allow exact alarms.",
          "commute_exact_alarms_note",
          ui.controls.onOpenExactAlarmSettings,
        )
      }
    }
  }
}

@Composable
private fun CommuteRow(commute: Commute, ui: CommuteUi) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      commuteRowText(commute, ui.complexName(commute), ui.controls.use24Hour),
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodyMedium,
      modifier =
        Modifier.weight(1f)
          .heightIn(min = 48.dp)
          .clickable(role = Role.Button) { ui.edit(commute) }
          .wrapContentHeight(Alignment.CenterVertically)
          .padding(vertical = Spacing.sm)
          .testTag("commute_row_${commute.id}"),
    )
    Switch(
      checked = commute.enabled,
      onCheckedChange = { ui.toggle(commute) },
      modifier = Modifier.testTag("commute_switch_${commute.id}"),
    )
  }
}

@Composable
private fun CommuteNote(text: String, tag: String, onClick: () -> Unit) {
  TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(text) }
}

/** The "Add commute alert" button on the station's arrivals card. */
@Composable
internal fun AddCommuteButton(onClick: () -> Unit) {
  TextButton(onClick = onClick, modifier = Modifier.testTag("status_add_commute")) {
    Text("Add commute alert")
  }
}
