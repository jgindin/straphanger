package com.straphanger.tools.basemap

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One GeoJSON feature: its properties as strings, and its geometry already quantised. */
class Feature(val properties: Map<String, String>, val geometry: Geometry?)

/** The geometry types the NYC Open Data exports use. Rings and lines are lists of points. */
sealed interface Geometry {
  /** A polygon is its outer ring followed by its holes. */
  class Polygons(val polygons: List<List<List<QPoint>>>) : Geometry

  class Lines(val lines: List<List<QPoint>>) : Geometry
}

object GeoJsonReader {
  fun read(file: File): List<Feature> = parse(file.readText())

  fun parse(text: String): List<Feature> =
    Json.parseToJsonElement(text).jsonObject.getValue("features").jsonArray.map { feature ->
      val obj = feature.jsonObject
      Feature(properties = properties(obj["properties"]), geometry = geometry(obj["geometry"]))
    }

  private fun properties(element: JsonElement?): Map<String, String> =
    (element as? JsonObject)
      ?.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.let { key to it }
      }
      ?.toMap()
      .orEmpty()

  private fun geometry(element: JsonElement?): Geometry? {
    val obj = element as? JsonObject
    val coordinates = obj?.get("coordinates") as? JsonArray
    return when (coordinates?.let { obj["type"]?.jsonPrimitive?.contentOrNull }) {
      "Polygon" -> Geometry.Polygons(listOf(polygon(coordinates)))
      "MultiPolygon" -> Geometry.Polygons(coordinates.map { polygon(it.jsonArray) })
      "LineString" -> Geometry.Lines(listOf(points(coordinates)))
      "MultiLineString" -> Geometry.Lines(coordinates.map { points(it.jsonArray) })
      else -> null
    }
  }

  private fun polygon(rings: JsonArray): List<List<QPoint>> = rings.map { points(it.jsonArray) }

  private fun points(positions: JsonArray): List<QPoint> = positions.map { position ->
    val pair = position.jsonArray
    quantise(lng = pair[0].jsonPrimitive.double, lat = pair[1].jsonPrimitive.double)
  }
}
