package com.straphanger.tools.basemap

import kotlin.math.abs
import kotlinx.serialization.Serializable

/** The generated file, `nyc_basemap.json`. Every coordinate list is [deltaEncode]d. */
@Serializable
class BasemapFile(
  val version: Int,
  val sourceDate: String,
  /** Coordinates are integers in units of 1 / [unitsPerDegree] degree. */
  val unitsPerDegree: Int,
  /** Each borough's land, outer rings anticlockwise and holes clockwise. */
  val land: List<LandArea>,
  /** Park rings, oriented like [land]. */
  val parks: List<IntArray>,
  /** Highways and the major bridges, as polylines. */
  val highways: List<IntArray>,
  /** Major streets and their bridges, as polylines. */
  val arterials: List<IntArray>,
)

@Serializable class LandArea(val name: String, val rings: List<IntArray>)

/** What goes into the basemap, and how much detail is kept. */
data class BasemapRules(
  /**
   * Douglas-Peucker tolerance. At the map's largest zoom (5x) one screen pixel is about 5 m on a
   * 1080 x 2160 phone, so a 2 m error is under half a pixel.
   */
  val toleranceMetres: Double = 2.0,
  /**
   * Parks smaller than this are left out. Every park is kept today; raise it to shrink the file.
   */
  val minParkAcres: Double = 0.0,
  /** Rings (islands, holes) smaller than this after simplification are dropped. */
  val minRingSquareMetres: Double = 400.0,
)

/** Road classes, from the Street Centerline's `rw_type` and `carto_display_level`. */
enum class RoadClass {
  HIGHWAY,
  ARTERIAL;

  companion object {
    private const val RW_STREET = "1"
    private const val RW_HIGHWAY = "2"
    private const val RW_BRIDGE = "3"
    private val TOP_LEVEL = setOf("10")
    private val MAJOR_STREET_LEVELS = setOf("10", "20")
    private val ARTERIAL_BRIDGE_LEVELS = setOf("20", "30")

    /**
     * Highways (`rw_type` 2) and bridges at the top display level are [HIGHWAY]. Streets at display
     * levels 10 and 20 and bridges at 20 and 30 are [ARTERIAL]. Everything else, including tunnels
     * and ramps, is left out.
     */
    fun of(rwType: String?, displayLevel: String?): RoadClass? =
      when {
        rwType == RW_HIGHWAY -> HIGHWAY
        rwType == RW_BRIDGE && displayLevel in TOP_LEVEL -> HIGHWAY
        rwType == RW_STREET && displayLevel in MAJOR_STREET_LEVELS -> ARTERIAL
        rwType == RW_BRIDGE && displayLevel in ARTERIAL_BRIDGE_LEVELS -> ARTERIAL
        else -> null
      }
  }
}

/** Counts printed after a run. */
data class BasemapReport(
  val boroughs: Int,
  val landRings: Int,
  val parks: Int,
  val parkRings: Int,
  val highwayLines: Int,
  val arterialLines: Int,
  val pointsIn: Int,
  val pointsOut: Int,
)

class BasemapBuilder(private val rules: BasemapRules = BasemapRules()) {

  private var pointsIn = 0
  private var pointsOut = 0

  fun build(
    boroughs: List<Feature>,
    parks: List<Feature>,
    roads: List<Feature>,
    sourceDate: String,
  ): Pair<BasemapFile, BasemapReport> {
    pointsIn = 0
    pointsOut = 0
    val land =
      boroughs
        .sortedBy { it.properties["borocode"].orEmpty() }
        .map { LandArea(it.properties["boroname"].orEmpty(), polygonRings(it.geometry)) }
    val keptParks =
      parks
        .filter { (it.properties["acres"]?.toDoubleOrNull() ?: 0.0) >= rules.minParkAcres }
        .sortedBy { it.properties["gispropnum"].orEmpty() }
    val parkRings = keptParks.flatMap { polygonRings(it.geometry) }
    val roadsByClass =
      roads
        .sortedBy { it.properties["objectid"]?.toLongOrNull() ?: Long.MAX_VALUE }
        .groupBy { RoadClass.of(it.properties["rw_type"], it.properties["carto_display_level"]) }
    val highways = roadLines(roadsByClass[RoadClass.HIGHWAY].orEmpty())
    val arterials = roadLines(roadsByClass[RoadClass.ARTERIAL].orEmpty())
    val file =
      BasemapFile(
        version = 1,
        sourceDate = sourceDate,
        unitsPerDegree = UNITS_PER_DEGREE,
        land = land,
        parks = parkRings,
        highways = highways,
        arterials = arterials,
      )
    val report =
      BasemapReport(
        boroughs = land.size,
        landRings = land.sumOf { it.rings.size },
        parks = keptParks.size,
        parkRings = parkRings.size,
        highwayLines = highways.size,
        arterialLines = arterials.size,
        pointsIn = pointsIn,
        pointsOut = pointsOut,
      )
    return file to report
  }

  /** Simplified, oriented and encoded rings: outer rings anticlockwise, holes clockwise. */
  private fun polygonRings(geometry: Geometry?): List<IntArray> {
    val polygons = (geometry as? Geometry.Polygons)?.polygons.orEmpty()
    return polygons.flatMap { rings ->
      rings.mapIndexedNotNull { index, ring ->
        pointsIn += ring.size
        simplifyRing(ring, rules.toleranceMetres)
          ?.takeIf { abs(signedAreaSquareMetres(it)) >= rules.minRingSquareMetres }
          ?.oriented(anticlockwise = index == 0)
          ?.also { pointsOut += it.size }
          ?.let(::deltaEncode)
      }
    }
  }

  private fun roadLines(features: List<Feature>): List<IntArray> {
    val pieces =
      features
        .flatMap { (it.geometry as? Geometry.Lines)?.lines.orEmpty() }
        .map { it.withoutRepeats() }
    pointsIn += pieces.sumOf { it.size }
    return chainLines(pieces).map { line ->
      simplifyLine(line, rules.toleranceMetres).also { pointsOut += it.size }.let(::deltaEncode)
    }
  }
}
