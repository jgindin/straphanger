package com.straphanger.app.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.straphanger.app.ui.MapColorMode
import com.straphanger.app.ui.theme.MtaColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Subway lines stay readable on the basemap, and unridden lines keep their colour. */
class MapColorsTest {

  private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
  }

  private val lineColors =
    listOf("1", "4", "7", "A", "B", "G", "J", "L", "N", "SI").map {
      MtaColors.mapLineColor(MtaColors.routeColor(it))
    } + listOf(MtaColors.UnriddenTrack, MtaColors.UnriddenStation)

  @Test
  fun linesAreClearlyVisibleOnLandWaterAndParks() {
    for (line in lineColors) {
      for (ground in listOf(MtaColors.BasemapWater, MtaColors.BasemapLand, MtaColors.BasemapPark)) {
        val now = contrast(line, ground)
        assertTrue(now >= 1.8f, "$line on $ground: $now")
      }
    }
  }

  @Test
  fun anUnriddenLineUsesItsFullRouteColour() {
    for (route in listOf("1", "4", "7", "B", "G", "J", "L", "N")) {
      val color = unriddenSegmentColor(listOf(route), null, MapColorMode.OFFICIAL_MTA_COLORS)
      assertEquals(MtaColors.routeColor(route), color)
      assertEquals(1f, color.alpha)
    }
    assertEquals(
      MtaColors.routeColor("4"),
      unriddenSegmentColor(listOf("4", "5"), "4", MapColorMode.OFFICIAL_MTA_COLORS),
    )
    assertEquals(
      MtaColors.UnriddenTrack,
      unriddenSegmentColor(listOf("4"), null, MapColorMode.FREQUENCY_HEATMAP),
    )
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
    assertTrue(MtaColors.BasemapWater.blue - MtaColors.BasemapWater.red > 0.25f)
  }
}
