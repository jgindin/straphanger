package com.straphanger.app.ui.map

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import com.straphanger.app.data.basemap.BasemapShape
import com.straphanger.app.data.basemap.NycBasemap

/**
 * The basemap projected onto the map's unit square once, at load time. Each shape is a [FloatArray]
 * of interleaved x, y in the same projection as the stations.
 */
class ProjectedBasemap
internal constructor(
  val sourceDate: String,
  internal val land: List<FloatArray>,
  internal val parks: List<FloatArray>,
  internal val highways: List<FloatArray>,
  internal val arterials: List<FloatArray>,
)

/** Projects every shape of [basemap] with the map's projection, without clamping. */
fun projectBasemap(basemap: NycBasemap): ProjectedBasemap =
  ProjectedBasemap(
    sourceDate = basemap.sourceDate,
    land = basemap.land.flatMap { area -> area.rings.map(::projectShape) },
    parks = basemap.parks.map(::projectShape),
    highways = basemap.highways.map(::projectShape),
    arterials = basemap.arterials.map(::projectShape),
  )

private fun projectShape(shape: BasemapShape): FloatArray {
  val out = FloatArray(shape.lngLat.size)
  for (i in out.indices step 2) {
    out[i] = projectLng(shape.lngLat[i])
    out[i + 1] = projectLat(shape.lngLat[i + 1])
  }
  return out
}

/**
 * The basemap as [Path]s in unit-square coordinates, built once per basemap. Fills are drawn with
 * the canvas scaled to the viewport. Roads are copied into [screenRoads] and transformed to screen
 * pixels before stroking, so their width is in pixels whatever the zoom; the scratch path and the
 * matrix are reused, so panning and zooming allocate no geometry.
 */
internal class BasemapPaths(basemap: ProjectedBasemap) {
  val land = filledPath(basemap.land)
  val parks = filledPath(basemap.parks)
  val highways = linePath(basemap.highways)
  val arterials = linePath(basemap.arterials)
  private val screenRoads = Path()
  private val toScreen = Matrix()

  /** [roads] in screen pixels for [viewport]; valid until the next call. */
  fun roadsOnScreen(roads: Path, viewport: MapViewport): Path {
    toScreen.reset()
    toScreen.values[Matrix.ScaleX] = viewport.width * viewport.zoom
    toScreen.values[Matrix.ScaleY] = viewport.height * viewport.zoom
    toScreen.values[Matrix.TranslateX] = viewport.pan.x
    toScreen.values[Matrix.TranslateY] = viewport.pan.y
    screenRoads.reset()
    screenRoads.addPath(roads)
    screenRoads.transform(toScreen)
    return screenRoads
  }

  private companion object {
    /** Closed rings, filled with the non-zero rule: the generator orients holes the other way. */
    fun filledPath(rings: List<FloatArray>): Path =
      Path().apply {
        fillType = PathFillType.NonZero
        rings.forEach { ring ->
          addPoints(ring)
          close()
        }
      }

    fun linePath(lines: List<FloatArray>): Path = Path().apply { lines.forEach { addPoints(it) } }

    private fun Path.addPoints(xy: FloatArray) {
      if (xy.size < 2) return
      moveTo(xy[0], xy[1])
      for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
    }
  }
}
