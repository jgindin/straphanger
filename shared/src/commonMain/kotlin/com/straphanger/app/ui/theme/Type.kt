package com.straphanger.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun style(size: Int, lineHeight: Int, weight: FontWeight): TextStyle =
  TextStyle(fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight)

/** The app's type scale (default font family); body text never goes below 13 sp. */
internal val StraphangerTypography =
  Typography(
    headlineSmall = style(22, 28, FontWeight.Bold),
    titleLarge = style(20, 26, FontWeight.Bold),
    titleMedium = style(16, 22, FontWeight.Bold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 22, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.SemiBold),
    labelSmall = style(11, 14, FontWeight.Medium),
  )

/** Tabular (fixed-width) digits, so live minutes, ages and stats don't jiggle as they change. */
internal fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
