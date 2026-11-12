package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.GeoPoint
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A subway activity read from a Timeline file, before it is snapped to stations. */
internal data class RawSubwaySegment(
  val startTimeEpochMs: Long,
  val endTimeEpochMs: Long,
  val startPoint: GeoPoint,
  val endPoint: GeoPoint,
  val explicitRouteId: String? = null,
)

/**
 * Reads the subway segments of the three Google Maps Timeline formats described on
 * [TimelineJsonImporter]. [isKnownRoute] says whether a cleaned-up line name such as "4" is a route
 * of the network.
 */
internal class TimelineSegmentParser(private val isKnownRoute: (String) -> Boolean) {

  /** The subway segments in file order: on-device segments first, then Takeout ones. */
  fun segments(root: JsonElement): List<RawSubwaySegment> =
    when (root) {
      // iOS on-device Timeline root array
      is JsonArray -> semanticSegments(root)
      is JsonObject ->
        semanticSegments(root["semanticSegments"]) + takeoutSegments(root["timelineObjects"])
      else -> emptyList()
    }

  /** Formats 1 and 2: an array of on-device "semanticSegments". */
  private fun semanticSegments(element: JsonElement?): List<RawSubwaySegment> =
    (element as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull {
      parseSemanticSegmentObject(it)
    }

  /** Format 3: Google Takeout "timelineObjects", each possibly holding an "activitySegment". */
  private fun takeoutSegments(element: JsonElement?): List<RawSubwaySegment> =
    (element as? JsonArray).orEmpty().mapNotNull { item ->
      ((item as? JsonObject)?.get("activitySegment") as? JsonObject)?.let {
        parseTakeoutActivitySegment(it)
      }
    }

  private fun parseSemanticSegmentObject(obj: JsonObject): RawSubwaySegment? {
    val activity = obj["activity"] as? JsonObject
    val topCandidate = activity?.get("topCandidate") as? JsonObject
    val type =
      topCandidate?.get("type")?.jsonPrimitive?.contentOrNull
        ?: activity?.get("type")?.jsonPrimitive?.contentOrNull
    return if (activity != null && type != null && isSubwayActivityType(type)) {
      subwaySemanticSegment(obj, activity, topCandidate)
    } else {
      null
    }
  }

  private fun subwaySemanticSegment(
    obj: JsonObject,
    activity: JsonObject,
    topCandidate: JsonObject?,
  ): RawSubwaySegment? {
    val startTimeStr = obj["startTime"]?.jsonPrimitive?.contentOrNull
    val startMs = startTimeStr?.let { parseIsoTimestampMs(it) } ?: return null
    val endTimeStr = obj["endTime"]?.jsonPrimitive?.contentOrNull ?: startTimeStr
    val endMs = parseIsoTimestampMs(endTimeStr) ?: (startMs + DEFAULT_SEGMENT_MS)
    val path = obj["timelinePath"]
    val startCoord =
      parseFlexibleGeoPoint(activity["start"]) ?: parsePointFromTimelinePath(path, isFirst = true)
    val endCoord =
      parseFlexibleGeoPoint(activity["end"]) ?: parsePointFromTimelinePath(path, isFirst = false)
    return if (startCoord != null && endCoord != null) {
      RawSubwaySegment(
        startTimeEpochMs = startMs,
        endTimeEpochMs = endMs,
        startPoint = startCoord,
        endPoint = endCoord,
        explicitRouteId =
          extractRouteBullet(
            (activity["transitPath"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
              ?: topCandidate?.get("transitLine")?.jsonPrimitive?.contentOrNull
          ),
      )
    } else {
      null
    }
  }

  private fun parseTakeoutActivitySegment(actSeg: JsonObject): RawSubwaySegment? {
    val actType =
      actSeg["activityType"]?.jsonPrimitive?.contentOrNull
        ?: (actSeg["topCandidate"] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
    return if (actType != null && isSubwayActivityType(actType)) {
      subwayTakeoutSegment(actSeg)
    } else {
      null
    }
  }

  private fun subwayTakeoutSegment(actSeg: JsonObject): RawSubwaySegment? {
    val durationObj = actSeg["duration"] as? JsonObject
    val startMs = takeoutTimestampMs(durationObj, "startTimestamp") ?: return null
    val endMs = takeoutTimestampMs(durationObj, "endTimestamp") ?: (startMs + DEFAULT_SEGMENT_MS)
    val startCoord = parseFlexibleGeoPoint(actSeg["startLocation"])
    val endCoord = parseFlexibleGeoPoint(actSeg["endLocation"])
    val transitPath = actSeg["transitPath"] as? JsonObject
    return if (startCoord != null && endCoord != null) {
      RawSubwaySegment(
        startTimeEpochMs = startMs,
        endTimeEpochMs = endMs,
        startPoint = startCoord,
        endPoint = endCoord,
        explicitRouteId =
          extractRouteBullet(
            transitPath?.get("name")?.jsonPrimitive?.contentOrNull
              ?: transitPath?.get("line")?.jsonPrimitive?.contentOrNull
          ),
      )
    } else {
      null
    }
  }

  private fun extractRouteBullet(rawLineName: String?): String? =
    rawLineName
      ?.takeIf { it.isNotBlank() }
      ?.trim()
      ?.uppercase()
      ?.removeSuffix(" TRAIN")
      ?.removeSuffix(" LINE")
      ?.removeSuffix(" EXPRESS")
      ?.removeSuffix(" LOCAL")
      ?.trim()
      ?.takeIf(isKnownRoute)
}

/**
 * Normalizes all 3 coordinate formats used by Google Maps Timeline:
 * - Format 1 (Android degree strings): {"latLng": "40.7347°, -73.9899°"} or "40.7347° N, 73.9899°
 *   W"
 * - Format 2 (iOS geo URIs): "geo:40.7347,-73.9899" or {"latLng": "geo:40.7347,-73.9899"}
 * - Format 3 (Takeout E7 integers): {"latitudeE7": 407347000, "longitudeE7": -739899000}
 */
fun parseFlexibleGeoPoint(element: JsonElement?): GeoPoint? =
  when (element) {
    null -> null
    is JsonObject -> geoPointFromObject(element)
    else -> geoPointFromString((element as? JsonPrimitive)?.contentOrNull)
  }

private fun geoPointFromObject(obj: JsonObject): GeoPoint? {
  val nested = obj["latLng"] ?: obj["point"]
  return if (nested != null) parseFlexibleGeoPoint(nested)
  else e7GeoPoint(obj) ?: decimalGeoPoint(obj)
}

private fun e7GeoPoint(obj: JsonObject): GeoPoint? {
  val latE7 = obj["latitudeE7"]?.jsonPrimitive?.intOrNull
  val lngE7 = obj["longitudeE7"]?.jsonPrimitive?.intOrNull
  return if (latE7 != null && lngE7 != null) {
    GeoPoint(latE7 / E7_PER_DEGREE, lngE7 / E7_PER_DEGREE)
  } else {
    null
  }
}

private fun decimalGeoPoint(obj: JsonObject): GeoPoint? {
  val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: obj["latitude"]?.jsonPrimitive?.doubleOrNull
  val lng = obj["lng"]?.jsonPrimitive?.doubleOrNull ?: obj["longitude"]?.jsonPrimitive?.doubleOrNull
  return if (lat != null && lng != null) GeoPoint(lat, lng) else null
}

/** "40.7347°, -73.9899°", "40.7347° N, 73.9899° W" or "geo:40.7347,-73.9899". */
private fun geoPointFromString(raw: String?): GeoPoint? {
  val parts = raw?.trim()?.removePrefix("geo:")?.trim()?.split(",").orEmpty()
  val lat = parts.getOrNull(0)?.let { parseCoordinateToken(it) }
  val lng = parts.getOrNull(1)?.let { parseCoordinateToken(it) }
  return if (lat != null && lng != null) GeoPoint(lat, lng) else null
}

private fun parseCoordinateToken(token: String): Double? {
  val upper = token.trim().uppercase()
  val isNegativeCardinal = upper.endsWith("S") || upper.endsWith("W")
  val numericOnly =
    upper
      .replace("°", "")
      .replace("N", "")
      .replace("S", "")
      .replace("E", "")
      .replace("W", "")
      .trim()
  return numericOnly.toDoubleOrNull()?.let { if (isNegativeCardinal && it > 0) -it else it }
}

private fun parsePointFromTimelinePath(pathElem: JsonElement?, isFirst: Boolean): GeoPoint? =
  (pathElem as? JsonArray)
    ?.let { if (isFirst) it.firstOrNull() else it.lastOrNull() }
    ?.let { parseFlexibleGeoPoint(it) }

/** A Takeout duration's `<key>` ISO timestamp, or else its `<key>Ms` epoch milliseconds. */
private fun takeoutTimestampMs(duration: JsonObject?, key: String): Long? =
  duration?.get(key)?.jsonPrimitive?.contentOrNull?.let { parseIsoTimestampMs(it) }
    ?: duration?.get("${key}Ms")?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun parseIsoTimestampMs(raw: String): Long? =
  Instant.parseOrNull(raw)?.toEpochMilliseconds()

private fun isSubwayActivityType(type: String): Boolean {
  val upper = type.uppercase()
  return upper == "IN_SUBWAY" || upper == "SUBWAY" || upper == "IN_TRAIN"
}

/** Length assumed for a segment with no readable end time. */
private const val DEFAULT_SEGMENT_MS = 15 * 60_000L

/** Google stores latitude and longitude as integer degrees times 10^7. */
private const val E7_PER_DEGREE = 1e7
