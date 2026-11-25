package com.straphanger.app.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.commuteTimeText
import com.straphanger.app.data.realtime.Direction
import kotlinx.datetime.DayOfWeek

private const val WORK_WEEK_DAYS = 5
private const val DEFAULT_HOUR = 8
private const val HOURS_PER_DAY = 24

/** What the editor's fields hold right now. */
private class CommuteForm(draft: CommuteDraft) {
  val existing = draft.existing
  var line by mutableStateOf(draft.routeId)
  var direction by mutableStateOf(draft.direction)
  var days by mutableStateOf(existing?.days ?: DayOfWeek.entries.take(WORK_WEEK_DAYS).toSet())
  var hour by mutableStateOf(existing?.hour ?: DEFAULT_HOUR)
  var minute by mutableStateOf(existing?.minute ?: 0)

  /** The end time, or null for none; the store only keeps both or neither. */
  var end by
    mutableStateOf(
      existing?.endHour?.let { endHour -> existing.endMinute?.let { endHour to it } },
    )
  var pickingStart by mutableStateOf(false)
  var pickingEnd by mutableStateOf(false)

  val valid: Boolean
    get() = days.isNotEmpty() && line.isNotEmpty()

  fun toggleDay(day: DayOfWeek) {
    days = if (day in days) days - day else days + day
  }

  fun build(draft: CommuteDraft, id: String) =
    Commute(
      id = existing?.id ?: id,
      routeId = line,
      complexId = draft.complexId,
      direction = direction,
      days = days,
      hour = hour,
      minute = minute,
      endHour = end?.first,
      endMinute = end?.second,
      enabled = existing?.enabled ?: true,
    )
}

/** The add / edit dialog for one commute; Save is off while no day is chosen. */
@Composable
internal fun CommuteEditorDialog(draft: CommuteDraft, ui: CommuteUi) {
  val form = remember { CommuteForm(draft) }
  val use24Hour = ui.controls.use24Hour
  AlertDialog(
    onDismissRequest = ui::closeEditor,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.testTag("commute_editor"),
    title = {
      Text(
        if (form.existing == null) "Add commute alert" else "Edit commute alert",
        color = MaterialTheme.colorScheme.onSurface,
      )
    },
    text = { CommuteFields(form, draft.routes, use24Hour) },
    confirmButton = {
      Row {
        form.existing?.let {
          TextButton(onClick = { ui.delete(it) }, modifier = Modifier.testTag("commute_delete")) {
            Text("Delete")
          }
        }
        TextButton(
          enabled = form.valid,
          modifier = Modifier.testTag("commute_save"),
          onClick = { ui.save(form.build(draft, ui.newId())) },
        ) {
          Text("Save")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = ui::closeEditor, modifier = Modifier.testTag("commute_cancel")) {
        Text("Cancel")
      }
    },
  )
  TimeDialogs(form, use24Hour)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommuteFields(form: CommuteForm, routes: List<String>, use24Hour: Boolean) {
  Column(
    modifier = Modifier.verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      routes.forEach { ChoiceChip(it, form.line == it, "commute_line_$it") { form.line = it } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Direction.entries.forEach {
        ChoiceChip(directionText(it), form.direction == it, "commute_direction_${it.name}") {
          form.direction = it
        }
      }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      DayOfWeek.entries.forEach { day ->
        val label = day.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
        ChoiceChip(label, day in form.days, "commute_day_${day.name}") { form.toggleDay(day) }
      }
    }
    TimeButton("Time", commuteTimeText(form.hour, form.minute, use24Hour), "commute_time") {
      form.pickingStart = true
    }
    val end = form.end
    TimeButton(
      "Show on widget until",
      if (end == null) "Not set" else commuteTimeText(end.first, end.second, use24Hour),
      "commute_end_time",
    ) {
      form.pickingEnd = true
    }
  }
}

/** The picker for whichever of the start and end times is being chosen. */
@Composable
private fun TimeDialogs(form: CommuteForm, use24Hour: Boolean) {
  if (form.pickingStart) {
    CommuteTimeDialog(
      "Time",
      form.hour,
      form.minute,
      use24Hour,
      onPick = { hour, minute ->
        form.hour = hour
        form.minute = minute
        form.pickingStart = false
      },
      onDismiss = { form.pickingStart = false },
    )
  }
  if (form.pickingEnd) {
    // With no end time yet, the picker starts an hour after the start.
    val end = form.end ?: ((form.hour + 1) % HOURS_PER_DAY to form.minute)
    CommuteTimeDialog(
      "Show on widget until",
      end.first,
      end.second,
      use24Hour,
      onPick = { hour, minute ->
        form.end = hour to minute
        form.pickingEnd = false
      },
      onDismiss = { form.pickingEnd = false },
      onClear = {
        form.end = null
        form.pickingEnd = false
      },
    )
  }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label) },
    modifier = Modifier.testTag(tag),
  )
}

/** A time's [label] over a button showing [value]; the button carries [tag]. */
@Composable
private fun TimeButton(label: String, value: String, tag: String, onClick: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      label,
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(value) }
  }
}
