package com.straphanger.app.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * The app's dark Material colour scheme. Every role a screen uses is set: blue-greys for the
 * surfaces, light blue `primary` for text buttons, links and selections, MTA blue
 * `primaryContainer` for filled buttons, and gold `tertiary` for highlights only.
 */
internal val StraphangerColorScheme =
  darkColorScheme(
    background = MtaColors.DarkBg,
    onBackground = MtaColors.TextPrimary,
    surface = MtaColors.DarkBg,
    onSurface = MtaColors.TextPrimary,
    surfaceVariant = Color(0xFF1E2636),
    onSurfaceVariant = MtaColors.TextSecondary,
    surfaceContainerLowest = MtaColors.DarkBg,
    surfaceContainerLow = Color(0xFF10151F),
    surfaceContainer = MtaColors.CardBg,
    surfaceContainerHigh = Color(0xFF1E2636),
    surfaceContainerHighest = Color(0xFF2B3548),
    outline = Color(0xFF33435E),
    outlineVariant = MtaColors.CardBorder,
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF0039A6),
    onPrimaryContainer = Color.White,
    secondary = MtaColors.TextSecondary,
    onSecondary = Color(0xFF0B0E14),
    secondaryContainer = Color(0xFF2B3548),
    onSecondaryContainer = MtaColors.TextPrimary,
    tertiary = MtaColors.AccentGold,
    onTertiary = Color.Black,
    error = Color(0xFFFF8A80),
    onError = Color.Black,
    errorContainer = Color(0xFF2A1416),
    onErrorContainer = Color(0xFFFFDAD6),
  )

@Composable
internal fun StraphangerTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = StraphangerColorScheme,
    typography = StraphangerTypography,
    shapes = StraphangerShapes,
  ) {
    CompositionLocalProvider(
      LocalContentColor provides MaterialTheme.colorScheme.onSurface,
      content = content,
    )
  }
}

/** A main action: MTA blue with white text. */
@Composable
internal fun primaryButtonColors(): ButtonColors =
  ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  )

/** A quiet action: the tonal blue-grey. */
@Composable
internal fun quietButtonColors(): ButtonColors =
  ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
  )

/** A confirming action (log a ride, confirm a line): the action green with white text. */
@Composable
internal fun confirmButtonColors(): ButtonColors =
  ButtonDefaults.buttonColors(
    containerColor = MtaColors.ActionGreen,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  )
