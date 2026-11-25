package com.straphanger.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.Spacing

@Composable
internal fun StatusBanner(message: String, failure: Boolean) {
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
        .padding(Spacing.md)
        .testTag("status_banner"),
  ) {
    Text(
      text = message,
      color = if (failure) MtaColors.Warning else MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    )
  }
}
