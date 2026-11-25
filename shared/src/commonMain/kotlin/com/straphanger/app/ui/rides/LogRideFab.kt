package com.straphanger.app.ui.rides

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.straphanger.app.ui.theme.Spacing
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_add

/**
 * How far the Rides list must scroll past its last row to clear the Log ride button: the extended
 * button's 56 dp height, the scaffold's 16 dp margin under it, and a gap. The scaffold's own bars
 * are already outside the list.
 */
internal val LogRideFabClearance: Dp = 56.dp + 16.dp + Spacing.lg

/** The Rides tab's "Log ride" button; just the + while the list is scrolled down ([expanded]). */
@Composable
internal fun LogRideFab(expanded: Boolean, onClick: () -> Unit) {
  ExtendedFloatingActionButton(
    text = { Text("Log ride") },
    icon = {
      // Named by its text while expanded; the + alone needs a name of its own.
      Icon(
        painterResource(Res.drawable.ic_add),
        contentDescription = if (expanded) null else "Log ride",
      )
    },
    onClick = onClick,
    expanded = expanded,
    modifier = Modifier.testTag("log_ride_fab"),
  )
}
