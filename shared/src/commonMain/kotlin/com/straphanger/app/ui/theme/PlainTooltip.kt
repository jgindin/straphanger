package com.straphanger.app.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable

/** Shows [text] in a plain tooltip when [content] is long-pressed or hovered. */
@OptIn(ExperimentalMaterial3Api::class) // TooltipBox is still experimental in Material 3
@Composable
internal fun WithTooltip(text: String, content: @Composable () -> Unit) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(text) } },
    state = rememberTooltipState(),
    content = content,
  )
}
