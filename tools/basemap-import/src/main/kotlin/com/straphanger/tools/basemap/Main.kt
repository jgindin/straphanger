package com.straphanger.tools.basemap

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

private const val USAGE =
  "Usage: basemap-import --boroughs <geojson> --parks <geojson> --roads <geojson> --out <json> " +
    "--source-date <YYYY-MM-DD>"

private val REQUIRED = listOf("boroughs", "parks", "roads", "out", "source-date")

fun main(args: Array<String>) {
  val options =
    parseArgs(args)
      ?: run {
        System.err.println(USAGE)
        exitProcess(2)
      }
  val sourceDate = options.getValue("source-date")
  require(Regex("""\d{4}-\d{2}-\d{2}""").matches(sourceDate)) { "--source-date must be YYYY-MM-DD" }
  val (basemap, report) =
    BasemapBuilder()
      .build(
        boroughs = GeoJsonReader.read(File(options.getValue("boroughs"))),
        parks = GeoJsonReader.read(File(options.getValue("parks"))),
        roads = GeoJsonReader.read(File(options.getValue("roads"))),
        sourceDate = sourceDate,
      )
  val out = File(options.getValue("out"))
  out.parentFile?.mkdirs()
  out.writeText(encodeBasemap(basemap))
  println("Wrote ${out.path} (${out.length()} bytes)")
  println(
    "boroughs=${report.boroughs} landRings=${report.landRings} parks=${report.parks} " +
      "parkRings=${report.parkRings} highwayLines=${report.highwayLines} " +
      "arterialLines=${report.arterialLines} points=${report.pointsIn}->${report.pointsOut}",
  )
}

/** The JSON text written to the output file: compact, with a final newline. */
fun encodeBasemap(basemap: BasemapFile): String =
  Json.encodeToString(BasemapFile.serializer(), basemap) + "\n"

private fun parseArgs(args: Array<String>): Map<String, String>? {
  val map = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val key = args[i].removePrefix("--")
    if (!args[i].startsWith("--") || i + 1 >= args.size) return null
    map[key] = args[i + 1]
    i += 2
  }
  return map.takeIf { it.keys.containsAll(REQUIRED) }
}
