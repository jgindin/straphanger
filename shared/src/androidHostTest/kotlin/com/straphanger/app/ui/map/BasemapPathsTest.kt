package com.straphanger.app.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.testutil.BundledBasemap
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BasemapPathsTest {

  private val paths = BasemapPaths(projectBasemap(BundledBasemap.data))

  @Test
  fun pathsCoverTheCityInUnitSquareCoordinates() {
    val land = paths.land.getBounds()
    assertTrue(land.left in -0.1f..0.1f && land.right in 0.9f..1.1f, "land $land")
    assertTrue(land.top in -0.1f..0.1f && land.bottom in 0.9f..1.1f, "land $land")
    assertTrue(!paths.parks.isEmpty && !paths.highways.isEmpty && !paths.arterials.isEmpty)
  }

  @Test
  fun roadsAreTransformedToScreenPixelsIntoOneReusedPath() {
    val unit = paths.highways.getBounds()
    val viewport = MapViewport(width = 1000f, height = 2000f, zoom = 2f, pan = Offset(-300f, -50f))
    val screen = paths.roadsOnScreen(paths.highways, viewport)
    val bounds = screen.getBounds()
    assertEquals(unit.left * 2000f - 300f, bounds.left, 0.5f)
    assertEquals(unit.bottom * 4000f - 50f, bounds.bottom, 0.5f)
    // The same scratch path every frame; the unit-square path is left as it was.
    assertSame(screen, paths.roadsOnScreen(paths.arterials, viewport))
    assertEquals(unit, paths.highways.getBounds())
  }
}
