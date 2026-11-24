package com.straphanger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.straphanger.app.ui.theme.Spacing

/**
 * A full-width rounded card with a border, holding a column of [content] spaced by [spacing].
 * [modifier] is applied after the card's padding (where screens put their test tags).
 */
@Composable
internal fun SectionCard(
  spacing: Dp,
  modifier: Modifier = Modifier,
  background: Color = MaterialTheme.colorScheme.surfaceContainer,
  borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
  content: @Composable ColumnScope.() -> Unit,
) {
  val shape = MaterialTheme.shapes.medium
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape)
        .background(background)
        .border(1.dp, borderColor, shape)
        .padding(Spacing.md)
        .then(modifier),
    verticalArrangement = Arrangement.spacedBy(spacing),
    content = content,
  )
}

/** A card's bold heading. */
@Composable
internal fun CardTitle(text: String, style: TextStyle = MaterialTheme.typography.titleMedium) {
  Text(text = text, color = MaterialTheme.colorScheme.onSurface, style = style)
}
