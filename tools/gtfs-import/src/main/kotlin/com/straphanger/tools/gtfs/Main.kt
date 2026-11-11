package com.straphanger.tools.gtfs

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

private const val USAGE =
    "Usage: gtfs-import --gtfs <zip> --stations <csv> --out <json> --source-date <YYYY-MM-DD> [--routes <route-metadata.json>]"

fun main(args: Array<String>) {
    val options = parseArgs(args) ?: run {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val gtfs = File(options.getValue("gtfs"))
    val stations = File(options.getValue("stations"))
    val out = File(options.getValue("out"))
    val sourceDate = options.getValue("source-date")
    val routes = File(options["routes"] ?: "tools/gtfs-import/route-metadata.json")
    require(Regex("""\d{4}-\d{2}-\d{2}""").matches(sourceDate)) { "--source-date must be YYYY-MM-DD" }

    val builder = NetworkBuilder(
        feed = GtfsReader.readFeed(gtfs),
        stationListings = GtfsReader.readStationListings(stations),
        routeMetadata = RouteMetadataFile.read(routes)
    )
    val network = builder.build(sourceDate)
    val json = Json { encodeDefaults = true }
    out.parentFile?.mkdirs()
    out.writeText(json.encodeToString(SubwayNetworkData.serializer(), network) + "\n")

    println("Wrote ${out.path} (${out.length()} bytes)")
    println("sourceDate=${network.sourceDate} stationCount=${network.stationCount} complexCount=${network.complexCount} " +
        "systemMiles=${"%.2f".format(network.systemMiles)} segments=${network.segments.size}")
    println("routes=${network.routes.joinToString(" ") { it.routeId }}")
    println("expandedPairs=${builder.report.expandedPairs} keptPairs=${builder.report.keptPairs} shapeFallbacks=${builder.report.shapeFallbacks}")
    builder.report.crossTrunkExpansions.forEach { println("cross-trunk expansion: $it") }
}

private fun parseArgs(args: Array<String>): Map<String, String>? {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i].removePrefix("--")
        if (!args[i].startsWith("--") || i + 1 >= args.size) return null
        map[key] = args[i + 1]
        i += 2
    }
    return map.takeIf { it.keys.containsAll(listOf("gtfs", "stations", "out", "source-date")) }
}
