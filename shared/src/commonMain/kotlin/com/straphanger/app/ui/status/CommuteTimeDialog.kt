package com.straphanger.app.ui.status

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Material's clock-face time picker for one of the editor's times, starting at [hour]:[minute]. OK
 * passes the picked time to [onPick]; Cancel and a tap outside call [onDismiss]. With [onClear] it
 * also offers "Clear", for a time that may be unset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommuteTimeDialog(
  title: String,
  hour: Int,
  minute: Int,
  use24Hour: Boolean,
  onPick: (hour: Int, minute: Int) -> Unit,
  onDismiss: () -> Unit,
  onClear: (() -> Unit)? = null,
) {
  val state = rememberTimePickerState(hour, minute, use24Hour)
  TimePickerDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.testTag("commute_time_dialog"),
    confirmButton = {
      TextButton(
        onClick = { onPick(state.hour, state.minute) },
        modifier = Modifier.testTag("commute_time_ok"),
      ) {
        Text("OK")
      }
    },
    dismissButton = {
      Row {
        onClear?.let {
          TextButton(onClick = it, modifier = Modifier.testTag("commute_time_clear")) {
            Text("Clear")
          }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("commute_time_cancel")) {
          Text("Cancel")
        }
      }
    },
  ) {
    TimePicker(state, modifier = Modifier.testTag("commute_time_picker"))
  }
}
