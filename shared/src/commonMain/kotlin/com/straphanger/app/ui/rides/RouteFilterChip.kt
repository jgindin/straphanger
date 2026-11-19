package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet

/** Says the history is limited to one line, and clears that limit when tapped. */
@Composable
internal fun RouteFilterChip(routeId: String, onClear: () -> Unit) {
  Row(
    modifier =
      Modifier.clip(RoundedCornerShape(20.dp))
        .background(MtaColors.CardBg)
        .clickable(onClick = onClear)
        .padding(horizontal = 10.dp, vertical = 6.dp)
        .testTag("route_filter_chip"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MtaRouteBullet(routeId = routeId, size = 20.dp, fontSize = 10.sp)
    Text("Only this line  ✕", color = Color.White, fontSize = 12.sp)
  }
}
