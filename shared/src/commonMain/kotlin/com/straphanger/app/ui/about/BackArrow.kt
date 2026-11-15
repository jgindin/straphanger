package com.straphanger.app.ui.about

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val ARROW_STROKE_DP = 2f
private const val ARROW_SIZE_DP = 24f

/**
 * The "<" arrow at the top left of a screen that goes back, in the position and shape Android users
 * expect. It is drawn here so the app needs no icon library for one glyph.
 */
@Composable
fun BackArrowButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  IconButton(onClick = onClick, modifier = modifier.semantics { contentDescription = "Back" }) {
    Canvas(Modifier.size(ARROW_SIZE_DP.dp)) {
      val stroke = Stroke(ARROW_STROKE_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      val w = size.width
      val h = size.height
      val middleY = h / 2
      val tipX = w * 0.15f
      val tailX = w * 0.9f
      val wing = h * 0.3f
      drawLine(Color.White, Offset(tipX, middleY), Offset(tailX, middleY), stroke.width, stroke.cap)
      drawLine(
        Color.White,
        Offset(tipX, middleY),
        Offset(tipX + wing, middleY - wing),
        stroke.width,
        stroke.cap,
      )
      drawLine(
        Color.White,
        Offset(tipX, middleY),
        Offset(tipX + wing, middleY + wing),
        stroke.width,
        stroke.cap,
      )
    }
  }
}
