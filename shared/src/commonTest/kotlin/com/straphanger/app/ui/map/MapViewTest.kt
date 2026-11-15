package com.straphanger.app.ui.map

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapViewTest {
  private val min = 0.5f
  private val max = 5f

  /** Where the unit-square point ([x], [y]) is drawn on a [w] by [h] canvas. */
  private fun screenOf(view: MapView, x: Float, y: Float, w: Float, h: Float): Offset =
    MapViewport(w, h, view).toScreen(x, y)

  private fun assertNear(expected: Offset, actual: Offset) {
    assertEquals(expected.x, actual.x, 0.01f)
    assertEquals(expected.y, actual.y, 0.01f)
  }

  @Test
  fun zoomingAboutTheCentreKeepsTheCentrePointWhereItIs() {
    val view = MapView(1.65f, Offset(-120f, -60f))
    val centre = Offset(540f, 900f)
    // The map point under the centre before zooming.
    val nx = (centre.x - view.pan.x) / (1080f * view.zoom)
    val ny = (centre.y - view.pan.y) / (1800f * view.zoom)

    val zoomedIn = view.zoomedAbout(1.25f, centre, min, max)
    val zoomedOut = view.zoomedAbout(1 / 1.25f, centre, min, max)

    assertNear(centre, screenOf(zoomedIn, nx, ny, 1080f, 1800f))
    assertNear(centre, screenOf(zoomedOut, nx, ny, 1080f, 1800f))
    assertEquals(1.65f * 1.25f, zoomedIn.zoom, 0.0001f)
  }

  @Test
  fun aPinchKeepsTheMapPointUnderTheFingersWhereItStarted() {
    val view = MapView(1f, Offset(30f, -20f))
    val focal = Offset(200f, 1400f)
    val nx = (focal.x - view.pan.x) / (1080f * view.zoom)
    val ny = (focal.y - view.pan.y) / (1800f * view.zoom)

    val pinched = view.zoomedAbout(2.5f, focal, min, max)

    assertNear(focal, screenOf(pinched, nx, ny, 1080f, 1800f))
  }

  @Test
  fun zoomIsLimitedAndAtTheLimitTheMapDoesNotMove() {
    val view = MapView(max, Offset(-400f, -300f))
    val atLimit = view.zoomedAbout(1.25f, Offset(540f, 900f), min, max)
    assertEquals(max, atLimit.zoom)
    assertEquals(view.pan, atLimit.pan)
    assertEquals(min, MapView(min, Offset.Zero).zoomedAbout(0.5f, Offset.Zero, min, max).zoom)
  }

  @Test
  fun panningMovesTheMapByTheFingerDelta() {
    assertEquals(
      Offset(15f, -5f),
      MapView(2f, Offset(10f, 0f)).pannedBy(Offset(5f, -5f)).pan,
    )
  }

  @Test
  fun theOpeningViewPutsTheWestEdgeOfTheNetworkAtTheLeftInsetOnATallScreen() {
    val bounds = MapBounds(minX = 0.02f, minY = 0.2f, maxX = 0.9f, maxY = 0.8f)
    val insets = Insets(24f, 300f, 24f, 24f)

    val view = fitView(bounds, 1080f, 2200f, insets)

    val west = screenOf(view, bounds.minX, 0.5f, 1080f, 2200f)
    val east = screenOf(view, bounds.maxX, 0.5f, 1080f, 2200f)
    assertEquals(24f, west.x, 0.01f)
    assertEquals(1080f - 24f, east.x, 0.01f)
    val north = screenOf(view, 0.5f, bounds.minY, 1080f, 2200f)
    val south = screenOf(view, 0.5f, bounds.maxY, 1080f, 2200f)
    assertTrue(north.y >= 300f)
    assertTrue(south.y <= 2200f - 24f)
  }

  @Test
  fun aWideScreenIsLimitedByHeightAndCentredAcross() {
    val bounds = MapBounds(0.1f, 0.1f, 0.5f, 0.9f)
    val view = fitView(bounds, 2000f, 800f, Insets(0f, 0f, 0f, 0f))
    val north = screenOf(view, 0.3f, bounds.minY, 2000f, 800f)
    val south = screenOf(view, 0.3f, bounds.maxY, 2000f, 800f)
    assertEquals(0f, north.y, 0.01f)
    assertEquals(800f, south.y, 0.01f)
    val west = screenOf(view, bounds.minX, 0.5f, 2000f, 800f)
    val east = screenOf(view, bounds.maxX, 0.5f, 2000f, 800f)
    assertEquals(2000f - east.x, west.x, 0.01f)
  }
}
