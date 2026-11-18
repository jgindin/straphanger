package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideIds
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.diagnostics.DiagnosticsLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlin.time.Clock
import kotlinx.io.IOException

/**
 * Parses shared Google Maps routes from:
 * 1. Plain-text share summaries ("From 14 St-Union Sq to 86 St via 4 train")
 * 2. Live Google Maps Transit Navigation notifications ("GMAPS_NOTIFICATION: 4 Train | Board at 14
 *    St-Union Sq • Exit at 86 St")
 * 3. Opaque maps.app.goo.gl short URLs by following HTTP 302 Location headers via Ktor
 * 4. iOS/Android deep links ("straphanger://share?from=L08&to=626&route=L", GTFS station IDs)
 *
 * A station name that matches stations in more than one complex (for example "86 St") is resolved
 * using the named line when there is one; otherwise the ride is marked as needing review.
 */
class GoogleMapsShareParser(
  private val graphEngine: SubwayGraphEngine,
  private val httpClient: HttpClient = HttpClient { followRedirects = false },
  private val clock: Clock = Clock.System,
) {
  private val shortUrlRegex =
    Regex("""https?://(?:maps\.app\.goo\.gl|goo\.gl/maps)/[A-Za-z0-9_-]+""")
  private val fromToViaRegex =
    Regex(
      """(?i)(?:(?:from|board\s+at)\s+)?([^\s•|][^•|\n]*?)""" +
        """\s+(?:to|•\s*exit\s+at)\s+([^•|\n]+?)""" +
        """(?:\s+via\s+([A-Z0-9]{1,3})(?:\s+train|\s+line)?)?$""",
    )
  private val headerRouteRegex = Regex("""\b([1-7]|[A-HJ-NP-Z]|SIR)\s+[Tt]rain\b""")
  private val dirUrlPathRegex = Regex("""/maps/dir/([^/]+)/([^/?#]+)""")

  /** When the shared ride happened and where the text came from. */
  private data class ShareContext(
    val timestampEpochMs: Long,
    val endTimeEpochMs: Long,
    val source: RideSource,
  )

  /**
   * @param timestampEpochMs when the ride started.
   * @param endTimeEpochMs when it ended, if known (e.g. a navigation notification's removal); 0
   *   means unknown, and the duration is estimated from the distance.
   */
  suspend fun parseAndResolveSharedRoute(
    sharedText: String,
    timestampEpochMs: Long = clock.now().toEpochMilliseconds(),
    endTimeEpochMs: Long = 0L,
  ): CompleteRideRecord? {
    val text = sharedText.trim()
    val source =
      if (text.startsWith("GMAPS_NOTIFICATION:")) RideSource.GMAPS_NOTIFICATION
      else RideSource.GMAPS_SHARE
    val context = ShareContext(timestampEpochMs, endTimeEpochMs, source)
    return fromDeepLink(text, context)
      ?: fromPlainText(text, context)
      ?: fromDirectionsUrl(text, context)
  }

  /** A custom scheme deep link: `straphanger://share?from=...&to=...&route=...`. */
  private fun fromDeepLink(text: String, context: ShareContext): CompleteRideRecord? {
    val isDeepLink = text.startsWith("straphanger://") || text.startsWith("nycsubway://")
    if (!isDeepLink) return null
    val params = parseQueryParams(text.substringAfter("?", ""))
    return rideBetween(
      origin = params["from"]?.let { resolveStation(it) },
      dest = params["to"]?.let { resolveStation(it) },
      preferredRoute = params["route"]?.uppercase(),
      context = context,
      idPrefix = "share",
      flagAmbiguity = false,
    )
  }

  /** A plain-text share summary or a live notification body. */
  private fun fromPlainText(text: String, context: ShareContext): CompleteRideRecord? {
    val headerRoute = headerRouteRegex.find(text)?.groupValues?.get(1)?.uppercase()
    return text.lines().firstNotNullOfOrNull { fromSummaryLine(it.trim(), headerRoute, context) }
  }

  private fun fromSummaryLine(
    line: String,
    headerRoute: String?,
    context: ShareContext,
  ): CompleteRideRecord? {
    val m = fromToViaRegex.find(line) ?: return null
    val originToken = m.groupValues[1].trim()
    val destToken = m.groupValues[2].replace(shortUrlRegex, "").trim()
    val routeToken =
      m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.uppercase() ?: headerRoute
    return rideBetween(
      origin = resolveStation(originToken, routeToken),
      dest = resolveStation(destToken, routeToken),
      preferredRoute = routeToken,
      context = context,
      idPrefix = "share_txt",
      flagAmbiguity = true,
    )
  }

  /** A `/maps/dir/<origin>/<dest>` URL, after expanding a maps.app.goo.gl short link. */
  private suspend fun fromDirectionsUrl(text: String, context: ShareContext): CompleteRideRecord? {
    val expandedUrl = shortUrlRegex.find(text)?.value?.let { resolveRedirectLocation(it) } ?: text
    val dirMatch = dirUrlPathRegex.find(expandedUrl) ?: return null
    return rideBetween(
      origin = resolveStation(decodeUrlToken(dirMatch.groupValues[1])),
      dest = resolveStation(decodeUrlToken(dirMatch.groupValues[2])),
      preferredRoute = null,
      context = context,
      idPrefix = "share_url",
      flagAmbiguity = true,
    )
  }

  /**
   * The ride between two different resolved stations, or null when either is missing or no route
   * joins them. With [flagAmbiguity], an ambiguous station name marks the ride as needing review.
   */
  private fun rideBetween(
    origin: StationMatch?,
    dest: StationMatch?,
    preferredRoute: String?,
    context: ShareContext,
    idPrefix: String,
    flagAmbiguity: Boolean,
  ): CompleteRideRecord? {
    if (origin == null || dest == null || origin.stationId == dest.stationId) return null
    val resolved =
      graphEngine.resolveOriginToDestinationAuto(
        originStationId = origin.stationId,
        destinationStationId = dest.stationId,
        preferredRouteId = preferredRoute,
        timestampEpochMs = context.timestampEpochMs,
      )
    val ambiguous = flagAmbiguity && (origin.isAmbiguous || dest.isAmbiguous)
    return resolved.legs
      .takeIf { it.isNotEmpty() }
      ?.let {
        SubwayDao.buildCompleteRideRecord(
          rideId = RideIds.newId(idPrefix),
          startTimeEpochMs = context.timestampEpochMs,
          endTimeEpochMs = context.endTimeEpochMs,
          source = context.source,
          resolved = resolved,
          graphEngine = graphEngine,
          forceNeedsReview = if (ambiguous) true else null,
        )
      }
  }

  /**
   * The `Location` header of [shortUrl]'s redirect, or null when there is none or the request
   * failed; a failure is logged and the share text is parsed as it is.
   */
  private suspend fun resolveRedirectLocation(shortUrl: String): String? =
    try {
      httpClient.get(shortUrl).headers[HttpHeaders.Location]
    } catch (e: IOException) {
      DiagnosticsLog.warn(LOG_TAG, "Could not expand $shortUrl", e)
      null
    } catch (e: IllegalArgumentException) {
      DiagnosticsLog.warn(LOG_TAG, "Could not expand $shortUrl", e)
      null
    }

  private data class StationMatch(val stationId: String, val isAmbiguous: Boolean)

  private fun resolveStation(token: String, preferredRoute: String? = null): StationMatch? {
    val trimmed = token.trim()
    val station = graphEngine.getStation(trimmed)
    val coordinates = parseCoordinates(trimmed)
    return when {
      station != null -> StationMatch(station.id, isAmbiguous = false)
      coordinates != null ->
        graphEngine
          .findNearestStation(
            coordinates.first,
            coordinates.second,
            preferredRouteId = preferredRoute,
          )
          ?.let { StationMatch(it.id, isAmbiguous = false) }
      else -> matchByName(trimmed, preferredRoute)
    }
  }

  /** Exact name matches first, then the station serving [preferredRoute]. */
  private fun matchByName(name: String, preferredRoute: String?): StationMatch? {
    val searched = graphEngine.searchStations(name)
    val matches = searched.filter { it.name.equals(name, ignoreCase = true) }.ifEmpty { searched }
    val onRoute = preferredRoute?.let { route -> matches.firstOrNull { route in it.daytimeRoutes } }
    val first = matches.firstOrNull()
    return when {
      onRoute != null -> StationMatch(onRoute.id, isAmbiguous = false)
      first != null ->
        StationMatch(first.id, isAmbiguous = matches.any { it.complexId != first.complexId })
      else -> null
    }
  }
}

private const val LOG_TAG = "StraphangerShare"

/** A coordinate pair such as "40.7347,-73.9899", or null. */
private fun parseCoordinates(token: String): Pair<Double, Double>? {
  val parts = token.split(",")
  val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
  val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
  return if (parts.size == 2 && lat != null && lng != null) lat to lng else null
}

private fun parseQueryParams(query: String): Map<String, String> {
  if (query.isBlank()) return emptyMap()
  return query
    .split("&")
    .mapNotNull { pair ->
      val idx = pair.indexOf("=")
      if (idx <= 0) null else pair.substring(0, idx) to decodeUrlToken(pair.substring(idx + 1))
    }
    .toMap()
}

private fun decodeUrlToken(raw: String): String {
  return raw
    .replace("+", " ")
    .replace("%20", " ")
    .replace("%2C", ",")
    .replace("%E2%80%93", "-")
    .replace("%2D", "-")
}
