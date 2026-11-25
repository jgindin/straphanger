package com.straphanger.app.testutil

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The WCAG contrast ratio between [a] and [b], from 1 (none) to 21 (black on white). */
fun contrast(a: Color, b: Color): Float {
  val la = a.luminance()
  val lb = b.luminance()
  return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
