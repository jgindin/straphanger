package com.straphanger.app.ui.rides

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.ui.theme.MtaRouteBullet
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_close

/** Says the history is limited to one line, and clears that limit when tapped. */
@Composable
internal fun RouteFilterChip(routeId: String, onClear: () -> Unit) {
  AssistChip(
    onClick = onClear,
    label = { Text("Only this line") },
    leadingIcon = { MtaRouteBullet(routeId = routeId, size = 20.dp, fontSize = 10.sp) },
    trailingIcon = {
      Icon(
        painterResource(Res.drawable.ic_close),
        contentDescription = null,
        modifier = Modifier.size(AssistChipDefaults.IconSize),
      )
    },
    modifier =
      Modifier.semantics { contentDescription = "Only this line, show all lines" }
        .testTag("route_filter_chip"),
  )
}
