package com.straphanger.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.straphanger.app.ui.theme.Spacing

/** A small primary-coloured heading over a rounded group of rows. */
@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Text(
      text = title,
      color = MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.labelLarge,
      modifier =
        Modifier.padding(horizontal = Spacing.md).testTag("settings_heading").semantics {
          heading()
        },
    )
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainer,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(content = content)
    }
  }
}

/** Rows sit on the section's surface rather than painting their own. */
@Composable
internal fun settingsItemColors(): ListItemColors =
  ListItemDefaults.colors(containerColor = Color.Transparent)
