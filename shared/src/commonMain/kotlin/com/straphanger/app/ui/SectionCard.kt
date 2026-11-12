package com.straphanger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.ui.theme.MtaColors

/**
 * A full-width rounded card with a border, holding a column of [content] spaced by [spacing].
 * [modifier] is applied after the card's padding (where screens put their test tags).
 */
@Composable
internal fun SectionCard(
  spacing: Dp,
  modifier: Modifier = Modifier,
  background: Color = MtaColors.CardBg,
  borderColor: Color = MtaColors.CardBorder,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(background)
        .border(1.dp, borderColor, RoundedCornerShape(14.dp))
        .padding(14.dp)
        .then(modifier),
    verticalArrangement = Arrangement.spacedBy(spacing),
    content = content,
  )
}

/** A card's bold white heading. */
@Composable
internal fun CardTitle(text: String, fontSize: TextUnit = 15.sp) {
  Text(text = text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
}
