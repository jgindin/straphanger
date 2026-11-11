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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MtaColors {
    val DarkBg = Color(0xFF0B0E14)
    val CardBg = Color(0xFF141A24)
    val CardBorder = Color(0xFF232C3D)
    val UnriddenTrack = Color(0xFF283142)
    val UnriddenStation = Color(0xFF3E4A61)
    val TextPrimary = Color(0xFFF3F6FA)
    val TextSecondary = Color(0xFF9BA7B8)
    val AccentGold = Color(0xFFFCCC0A)
    val AccentGreen = Color(0xFF00E676)

    fun parseHexColor(hex: String): Color {
        val clean = hex.removePrefix("#").trim()
        val rgb = clean.toLongOrNull(16) ?: return Color(0xFF0039A6)
        return Color(0xFF000000 or rgb)
    }

    fun routeColor(routeId: String): Color {
        return when (routeId.uppercase()) {
            "1", "2", "3" -> Color(0xFFEE352E)
            "4", "5", "6" -> Color(0xFF00933C)
            "7" -> Color(0xFFB933AD)
            "A", "C", "E", "SIR" -> Color(0xFF0039A6)
            "B", "D", "F", "M" -> Color(0xFFFF6319)
            "G" -> Color(0xFF6CBE45)
            "J", "Z" -> Color(0xFF996633)
            "L" -> Color(0xFFA7A9AC)
            "N", "Q", "R", "W" -> Color(0xFFFCCC0A)
            else -> Color(0xFF808183)
        }
    }

    fun routeTextColor(routeId: String): Color {
        return when (routeId.uppercase()) {
            "N", "Q", "R", "W" -> Color(0xFF000000)
            else -> Color(0xFFFFFFFF)
        }
    }

    fun heatmapColorForCount(rideCount: Int): Color {
        return when {
            rideCount <= 0 -> UnriddenTrack
            rideCount == 1 -> Color(0xFF00E5FF) // Cyan (1 ride)
            rideCount in 2..3 -> Color(0xFF00E676) // Neon Green (2-3 rides)
            rideCount in 4..5 -> Color(0xFFFFD600) // Electric Gold (4-5 rides)
            else -> Color(0xFFFF1744) // Hot Magenta/Crimson (6+ rides)
        }
    }
}

@Composable
fun MtaRouteBullet(
    routeId: String,
    size: Dp = 24.dp,
    fontSize: TextUnit = 12.sp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MtaColors.routeColor(routeId)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = routeId,
            color = MtaColors.routeTextColor(routeId),
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
