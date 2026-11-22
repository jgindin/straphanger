package com.straphanger.app.ui.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.daysText
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.ui.SectionCard
import com.straphanger.app.ui.theme.MtaColors

/** "Northbound" or "Southbound". */
internal fun directionText(direction: Direction): String =
  if (direction == Direction.NORTH) "Northbound" else "Southbound"

/** The 24-hour time as "7:45" or "18:10". */
internal fun commuteTimeText(hour: Int, minute: Int): String =
  "$hour:${minute.toString().padStart(2, '0')}"

/** "A Southbound · 168 St · Weekdays 7:45"; [stationName] is null for a station that is gone. */
internal fun commuteRowText(commute: Commute, stationName: String?): String =
  "${commute.routeId} ${directionText(commute.direction)} · " +
    "${stationName ?: "Station no longer listed"} · " +
    "${daysText(commute.days)} ${commuteTimeText(commute.hour, commute.minute)}"

/** The Commutes card at the top of the Status tab; nothing until there is a commute. */
internal fun LazyListScope.commutesSection(ui: CommuteUi) {
  if (ui.commutes.isEmpty()) return
  item {
    SectionCard(spacing = 8.dp, modifier = Modifier.testTag("status_commutes")) {
      Text("Commutes", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
      commuteRowText(commute, ui.complexName(commute)),
      color = Color.White,
      fontSize = 14.sp,
      modifier =
        Modifier.weight(1f)
          .clickable { ui.edit(commute) }
          .padding(vertical = 8.dp)
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
  Text(
    text,
    color = MtaColors.AccentGold,
    fontSize = 12.sp,
    modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp).testTag(tag),
  )
}

/** The "Add commute alert" button on the station's arrivals card. */
@Composable
internal fun AddCommuteButton(onClick: () -> Unit) {
  Text(
    "Add commute alert",
    color = MtaColors.AccentGold,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.clickable(onClick = onClick).padding(4.dp).testTag("status_add_commute"),
  )
}
