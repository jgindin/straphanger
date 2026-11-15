package com.straphanger.app.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.straphanger.app.ui.theme.MtaColors
import kotlin.test.Test
import kotlin.test.assertTrue

/** The basemap must not make the subway lines harder to read than the plain background did. */
class MapColorsTest {

  private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
  }

  private val lineColors =
    listOf("1", "4", "7", "A", "B", "G", "J", "L", "N", "SI").map(MtaColors::routeColor) +
      listOf(MtaColors.UnriddenTrack, MtaColors.UnriddenStation)

  @Test
  fun linesKeepMostOfTheirContrastOnLandAndParks() {
    for (line in lineColors) {
      val before = contrast(line, MtaColors.DarkBg)
      for (ground in listOf(MtaColors.BasemapWater, MtaColors.BasemapLand, MtaColors.BasemapPark)) {
        val now = contrast(line, ground)
        assertTrue(now >= before * 0.9f, "$line on $ground: $now, was $before")
      }
    }
  }

  @Test
  fun roadsStayFainterThanTheUnriddenTrack() {
    val track = MtaColors.UnriddenTrack.luminance()
    assertTrue(MtaColors.BasemapHighway.luminance() < track)
    assertTrue(MtaColors.BasemapArterial.luminance() < MtaColors.BasemapHighway.luminance())
    assertTrue(MtaColors.BasemapLand.luminance() < MtaColors.BasemapArterial.luminance())
  }

  @Test
  fun landWaterAndParksAreDistinct() {
    assertTrue(contrast(MtaColors.BasemapLand, MtaColors.BasemapWater) >= 1.05f)
    assertTrue(MtaColors.BasemapPark.green > MtaColors.BasemapLand.green)
    assertTrue(MtaColors.BasemapWater.luminance() < MtaColors.BasemapLand.luminance())
  }
}
