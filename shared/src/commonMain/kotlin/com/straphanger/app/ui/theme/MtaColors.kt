package com.straphanger.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAP_LINE_MIN_LUMINANCE = 0.1f

object MtaColors {
  // Raw palette values behind the colour scheme (StraphangerColorScheme). Composables use
  // MaterialTheme.colorScheme instead; these stay for code with no composition: the Glance widget
  // and the map's Canvas drawing.
  val DarkBg = Color(0xFF0B0E14)
  val CardBg = Color(0xFF141A24)
  val CardBorder = Color(0xFF232C3D)
  val TextPrimary = Color(0xFFF3F6FA)
  val TextSecondary = Color(0xFF9BA7B8)
  val AccentGold = Color(0xFFFCCC0A)

  /** Warnings: a failed refresh, service-alert markers, rides that need review. Not gold. */
  val Warning = Color(0xFFFFB74D)

  /** The background behind a warning card (the needs-review banner). */
  val WarningContainer = Color(0xFF281F12)

  /** Confirmations (a train is stopped, a ride was logged). */
  val Success = Color(0xFF00E676)

  /** The background behind an unlocked achievement. */
  val SuccessContainer = Color(0xFF192822)

  val UnriddenTrack = Color(0xFF7A869F)
  val UnriddenStation = Color(0xFFC3CCDD)

  // The basemap under the map: water a visible dark blue, land a lighter charcoal than the app
  // background, parks a muted green, and roads between land and the unridden track so they never
  // read as subway lines. MapColorsTest checks the contrast ratios.
  val BasemapWater = Color(0xFF123456)
  val BasemapLand = Color(0xFF2A2F3A)
  val BasemapPark = Color(0xFF2B4A38)
  val BasemapArterial = Color(0xFF383F4E)
  val BasemapHighway = Color(0xFF465068)

  /** Filled-button green; white text on it is 5.5:1 (the line green 0xFF00933C gives 4.0:1). */
  val ActionGreen = Color(0xFF007A33)

  /**
   * [line] lightened when it is dark enough to vanish into the dark basemap, as the A, C and E
   * blues do.
   */
  fun mapLineColor(line: Color): Color =
    if (line.luminance() >= MAP_LINE_MIN_LUMINANCE) line else lerp(line, Color.White, 0.4f)

  fun parseHexColor(hex: String): Color {
    val clean = hex.removePrefix("#").trim()
    val rgb = clean.toLongOrNull(16) ?: return Color(0xFF0039A6)
    return Color(0xFF000000 or rgb)
  }

  fun routeColor(routeId: String): Color {
    return when (routeId.uppercase()) {
      "1",
      "2",
      "3" -> Color(0xFFEE352E)
      "4",
      "5",
      "6",
      "6X" -> Color(0xFF00933C)
      "7",
      "7X" -> Color(0xFFB933AD)
      "A",
      "C",
      "E",
      "SI",
      "SIR" -> Color(0xFF0039A6)
      "B",
      "D",
      "F",
      "FX",
      "M" -> Color(0xFFFF6319)
      "G" -> Color(0xFF6CBE45)
      "J",
      "Z" -> Color(0xFF996633)
      "L" -> Color(0xFFA7A9AC)
      "N",
      "Q",
      "R",
      "W" -> Color(0xFFFCCC0A)
      else -> Color(0xFF808183)
    }
  }

  /** Text printed on a route bullet: GTFS IDs for shuttles and variants differ from the signage. */
  fun routeLabel(routeId: String): String =
    when (routeId.uppercase()) {
      "GS",
      "FS",
      "H" -> "S"
      "6X" -> "6"
      "7X" -> "7"
      "FX" -> "F"
      "SI" -> "SIR"
      else -> routeId
    }

  fun routeTextColor(routeId: String): Color {
    return when (routeId.uppercase()) {
      "N",
      "Q",
      "R",
      "W" -> Color(0xFF000000)
      else -> Color(0xFFFFFFFF)
    }
  }

  fun heatmapColorForCount(rideCount: Int): Color {
    return when {
      rideCount <= 0 -> UnriddenTrack
      rideCount == 1 -> Color(0xFF00E5FF) // Cyan (1 ride)
      rideCount in 2..3 -> Color(0xFF00E676) // Green (2-3 rides)
      rideCount in 4..5 -> Color(0xFFFFD600) // Gold (4-5 rides)
      else -> Color(0xFFFF1744) // Red (6+ rides)
    }
  }
}

@Composable
fun MtaRouteBullet(
  routeId: String,
  size: Dp = 24.dp,
  fontSize: TextUnit = 12.sp,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.size(size).clip(CircleShape).background(MtaColors.routeColor(routeId)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = MtaColors.routeLabel(routeId),
      color = MtaColors.routeTextColor(routeId),
      fontSize = fontSize,
      fontWeight = FontWeight.ExtraBold,
    )
  }
}
