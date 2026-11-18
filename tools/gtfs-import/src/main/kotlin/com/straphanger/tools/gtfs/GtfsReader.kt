package com.straphanger.tools.gtfs

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.util.zip.ZipFile

data class GtfsStop(
  val stopId: String,
  val name: String,
  val lat: Double,
  val lng: Double,
  val locationType: Int,
  val parentStation: String?,
)

data class GtfsRoute(
  val routeId: String,
  val shortName: String,
  val longName: String,
  val color: String,
  val textColor: String,
  val sortOrder: Int,
)

data class GtfsTrip(
  val tripId: String,
  val routeId: String,
  val serviceId: String,
  val directionId: Int,
  val shapeId: String?,
)

data class GtfsCalendar(
  val serviceId: String,
  /** Index 0 = Monday ... 6 = Sunday. */
  val days: List<Boolean>,
) {
  val runsWeekdays: Boolean
    get() = days.subList(0, 5).any { it }

  val runsSaturday: Boolean
    get() = days[5]
}

/**
 * A trip's stops, in stop_sequence order, using platform stop IDs as they appear in stop_times.txt.
 */
data class GtfsTripStops(
  val tripId: String,
  val stopIds: List<String>,
  /** Seconds after midnight of the service day; may exceed 24h. */
  val firstDepartureSeconds: Int,
)

data class GtfsFeed(
  val stops: List<GtfsStop>,
  val routes: List<GtfsRoute>,
  val trips: List<GtfsTrip>,
  val calendars: List<GtfsCalendar>,
  val tripStops: List<GtfsTripStops>,
  /** shape_id -> points in shape_pt_sequence order. */
  val shapes: Map<String, List<GeoPoint>>,
)

/** One row of the data.ny.gov "MTA Subway Stations" CSV. */
data class StationListing(
  val gtfsStopId: String,
  val complexId: String,
  val borough: Borough,
  val stopName: String,
)

object GtfsReader {

  /** Reads a GTFS feed from a zip file or from a directory of .txt files. */
  fun readFeed(source: File): GtfsFeed {
    if (source.isDirectory) {
      return readFeed { name -> File(source, name).takeIf { it.exists() }?.inputStream() }
    }
    ZipFile(source).use { zip ->
      return readFeed { name -> zip.getEntry(name)?.let { zip.getInputStream(it) } }
    }
  }

  private fun readFeed(open: (String) -> InputStream?): GtfsFeed {
    val tables = FeedTables(open)
    val stops = tables.stops()
    val routes = tables.routes()
    val trips = tables.trips()
    val calendars = tables.calendars()
    val tripStops = tables.tripStops(trips)
    return GtfsFeed(stops, routes, trips, calendars, tripStops, tables.shapes())
  }

  fun readStationListings(file: File): List<StationListing> = readStationListings(file.readText())

  fun readStationListings(csvText: String): List<StationListing> =
    Csv.readRows(csvText).map {
      StationListing(
        gtfsStopId = it.getValue("GTFS Stop ID"),
        complexId = it.getValue("Complex ID"),
        borough = parseBorough(it.getValue("Borough")),
        stopName = it.getValue("Stop Name"),
      )
    }

  fun parseBorough(code: String): Borough =
    when (code.trim()) {
      "M" -> Borough.MANHATTAN
      "Bk" -> Borough.BROOKLYN
      "Q" -> Borough.QUEENS
      "Bx" -> Borough.BRONX
      "SI" -> Borough.STATEN_ISLAND
      else -> error("Unknown borough code '$code'")
    }

  /** "25:10:30" -> seconds after midnight of the service day. */
  fun parseGtfsTime(value: String): Int {
    val parts = value.trim().split(":")
    require(parts.size == 3) { "Bad GTFS time '$value'" }
    return parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
  }
}

/** Reads each table of a GTFS feed whose files [open] returns, or null when one is missing. */
private class FeedTables(private val open: (String) -> InputStream?) {
  private fun table(name: String): List<Map<String, String>> {
    val rows = mutableListOf<Map<String, String>>()
    forEachRow(name) { rows.add(it) }
    return rows
  }

  private fun forEachRow(name: String, onRow: (Map<String, String>) -> Unit) {
    val stream = open(name) ?: error("GTFS feed has no $name")
    stream.bufferedReader().use { r -> Csv.forEachRow(r, onRow) }
  }

  fun stops(): List<GtfsStop> =
    table("stops.txt").map {
      GtfsStop(
        stopId = it.getValue("stop_id"),
        name = it.getValue("stop_name"),
        lat = it.getValue("stop_lat").toDouble(),
        lng = it.getValue("stop_lon").toDouble(),
        locationType = it["location_type"]?.toIntOrNull() ?: 0,
        parentStation = it["parent_station"]?.takeIf { p -> p.isNotBlank() },
      )
    }

  fun routes(): List<GtfsRoute> =
    table("routes.txt").mapIndexed { idx, row ->
      GtfsRoute(
        routeId = row.getValue("route_id"),
        shortName = row["route_short_name"].orEmpty(),
        longName = row["route_long_name"].orEmpty(),
        color = row["route_color"].orEmpty().ifBlank { "808183" },
        textColor = row["route_text_color"].orEmpty().ifBlank { "FFFFFF" },
        sortOrder = row["route_sort_order"]?.toIntOrNull() ?: idx,
      )
    }

  fun trips(): List<GtfsTrip> =
    table("trips.txt").map {
      GtfsTrip(
        tripId = it.getValue("trip_id"),
        routeId = it.getValue("route_id"),
        serviceId = it.getValue("service_id"),
        directionId = it["direction_id"]?.toIntOrNull() ?: 0,
        shapeId = it["shape_id"]?.takeIf { s -> s.isNotBlank() },
      )
    }

  fun calendars(): List<GtfsCalendar> =
    table("calendar.txt").map { row ->
      GtfsCalendar(
        serviceId = row.getValue("service_id"),
        days =
          listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").map {
            row[it] == "1"
          },
      )
    }

  private data class StopTime(val stopId: String, val sequence: Int, val departure: String)

  /** Streams stop_times.txt, which is too large to hold as rows. */
  fun tripStops(trips: List<GtfsTrip>): List<GtfsTripStops> {
    val byTrip = HashMap<String, MutableList<StopTime>>()
    forEachRow("stop_times.txt") { row ->
      byTrip
        .getOrPut(row.getValue("trip_id")) { mutableListOf() }
        .add(
          StopTime(
            stopId = row.getValue("stop_id"),
            sequence = row.getValue("stop_sequence").toInt(),
            departure = row["departure_time"].orEmpty().ifBlank { row["arrival_time"].orEmpty() },
          ),
        )
    }
    return trips.mapNotNull { trip ->
      byTrip[trip.tripId]
        ?.sortedBy { it.sequence }
        ?.let { times ->
          GtfsTripStops(
            tripId = trip.tripId,
            stopIds = times.map { it.stopId },
            firstDepartureSeconds = GtfsReader.parseGtfsTime(times.first().departure),
          )
        }
    }
  }

  /** shapes.txt is optional; a feed without it has no shapes. */
  fun shapes(): Map<String, List<GeoPoint>> {
    val shapePoints = HashMap<String, MutableList<Pair<Int, GeoPoint>>>()
    open("shapes.txt")?.bufferedReader()?.use { reader ->
      Csv.forEachRow(reader) { row ->
        shapePoints
          .getOrPut(row.getValue("shape_id")) { mutableListOf() }
          .add(
            row.getValue("shape_pt_sequence").toInt() to
              GeoPoint(
                row.getValue("shape_pt_lat").toDouble(),
                row.getValue("shape_pt_lon").toDouble(),
              ),
          )
      }
    }
    return shapePoints.mapValues { (_, pts) -> pts.sortedBy { it.first }.map { it.second } }
  }
}

/**
 * Minimal RFC 4180 CSV reader: quoted fields, doubled quotes, CRLF or LF line ends, optional BOM.
 */
object Csv {
  fun readRows(text: String): List<Map<String, String>> {
    val rows = mutableListOf<Map<String, String>>()
    forEachRow(StringReader(text)) { rows.add(it) }
    return rows
  }

  /** Streams rows as header -> value maps without holding the whole file in memory. */
  fun forEachRow(reader: Reader, onRow: (Map<String, String>) -> Unit) {
    val input = if (reader is BufferedReader) reader else BufferedReader(reader)
    var header: List<String>? = null
    forEachRecord(input) { rec ->
      val h = header
      if (h == null) {
        header = rec.mapIndexed { i, name ->
          if (i == 0) name.removePrefix("\uFEFF").trim() else name.trim()
        }
      } else if (rec.any { it.isNotEmpty() }) {
        val row = HashMap<String, String>(h.size * 2)
        for (i in h.indices) row[h[i]] = rec.getOrElse(i) { "" }
        onRow(row)
      }
    }
  }

  private fun forEachRecord(input: BufferedReader, onRecord: (List<String>) -> Unit) {
    CsvRecordReader(input, onRecord).readAll()
  }
}

/** Splits [input] into records of fields, handing each to [onRecord]. */
private class CsvRecordReader(
  private val input: BufferedReader,
  private val onRecord: (List<String>) -> Unit,
) {
  private var fields = ArrayList<String>()
  private val field = StringBuilder()
  private var inQuotes = false
  private var sawAny = false

  fun readAll() {
    var code = input.read()
    while (code >= 0) {
      val c = code.toChar()
      sawAny = true
      if (inQuotes) readQuoted(c) else readUnquoted(c)
      code = input.read()
    }
    if (sawAny) {
      endField()
      onRecord(fields)
    }
  }

  private fun readQuoted(c: Char) {
    if (c == '"') {
      input.mark(1)
      val next = input.read()
      if (next == '"'.code) {
        field.append('"')
      } else {
        inQuotes = false
        if (next >= 0) input.reset()
      }
    } else {
      field.append(c)
    }
  }

  private fun readUnquoted(c: Char) {
    when (c) {
      '"' -> inQuotes = true
      ',' -> endField()
      '\r' -> Unit
      '\n' -> {
        endField()
        onRecord(fields)
        fields = ArrayList(fields.size)
        sawAny = false
      }
      else -> field.append(c)
    }
  }

  private fun endField() {
    fields.add(field.toString())
    field.setLength(0)
  }
}
