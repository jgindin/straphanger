package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders

/**
 * Parses shared Google Maps routes from:
 * 1. Plain-text share summaries ("From 14 St-Union Sq to 86 St via 4 train")
 * 2. Live Google Maps Transit Navigation notifications ("GMAPS_NOTIFICATION: 4 Train | Board at 14 St-Union Sq • Exit at 86 St")
 * 3. Opaque maps.app.goo.gl short URLs by following HTTP 302 Location headers via Ktor
 * 4. iOS/Android deep links ("straphanger://share?from=L_BEDFORD&to=LEX_86&route=L")
 */
class GoogleMapsShareParser(
    private val graphEngine: SubwayGraphEngine,
    private val httpClient: HttpClient = HttpClient { followRedirects = false }
) {
    private val shortUrlRegex = Regex("""https?://(?:maps\.app\.goo\.gl|goo\.gl/maps)/[A-Za-z0-9_-]+""")
    private val fromToViaRegex = Regex(
        """(?i)(?:from|board\s+at)\s+([^•|\n]+?)\s+(?:to|•\s*exit\s+at)\s+([^•|\n]+?)(?:\s+via\s+([A-Z0-9]{1,3})(?:\s+train|\s+line)?)?$"""
    )
    private val dirUrlPathRegex = Regex("""/maps/dir/([^/]+)/([^/?#]+)""")

    suspend fun parseAndResolveSharedRoute(
        sharedText: String,
        timestampEpochMs: Long = 1794250800000L
    ): CompleteRideRecord? {
        val text = sharedText.trim()
        val isNotification = text.startsWith("GMAPS_NOTIFICATION:")
        val source = if (isNotification) RideSource.GMAPS_NOTIFICATION else RideSource.GMAPS_SHARE

        // 1. Check custom scheme deep link: straphanger://share?from=...&to=...&route=...
        if (text.startsWith("straphanger://") || text.startsWith("nycsubway://")) {
            val params = parseQueryParams(text.substringAfter("?", ""))
            val fromId = params["from"]?.let { resolveStationToken(it) }
            val toId = params["to"]?.let { resolveStationToken(it) }
            val route = params["route"]?.uppercase()
            if (fromId != null && toId != null && fromId != toId) {
                val resolved = graphEngine.resolveOriginToDestinationAuto(
                    originStationId = fromId,
                    destinationStationId = toId,
                    preferredRouteId = route,
                    timestampEpochMs = timestampEpochMs
                )
                if (resolved.legs.isNotEmpty()) {
                    return SubwayDao.buildCompleteRideRecord(
                        rideId = "share_${timestampEpochMs}",
                        startTimeEpochMs = timestampEpochMs,
                        endTimeEpochMs = 0L,
                        source = source,
                        resolved = resolved,
                        graphEngine = graphEngine
                    )
                }
            }
        }

        // 2. Check plain-text summary or live notification body
        extractFromPlainText(text, timestampEpochMs, source)?.let { return it }

        // 3. Check for maps.app.goo.gl short link and unpack HTTP 302 Location header
        val shortUrlMatch = shortUrlRegex.find(text)?.value
        val expandedUrl = if (shortUrlMatch != null) {
            resolveRedirectLocation(shortUrlMatch) ?: text
        } else {
            text
        }

        val dirMatch = dirUrlPathRegex.find(expandedUrl)
        if (dirMatch != null) {
            val rawOrigin = decodeUrlToken(dirMatch.groupValues[1])
            val rawDest = decodeUrlToken(dirMatch.groupValues[2])
            val originId = resolveStationToken(rawOrigin)
            val destId = resolveStationToken(rawDest)
            if (originId != null && destId != null && originId != destId) {
                val resolved = graphEngine.resolveOriginToDestinationAuto(
                    originStationId = originId,
                    destinationStationId = destId,
                    timestampEpochMs = timestampEpochMs
                )
                if (resolved.legs.isNotEmpty()) {
                    return SubwayDao.buildCompleteRideRecord(
                        rideId = "share_url_${timestampEpochMs}",
                        startTimeEpochMs = timestampEpochMs,
                        endTimeEpochMs = 0L,
                        source = source,
                        resolved = resolved,
                        graphEngine = graphEngine
                    )
                }
            }
        }

        return null
    }

    private fun extractFromPlainText(
        text: String,
        timestampEpochMs: Long,
        source: RideSource
    ): CompleteRideRecord? {
        val headerRoute = Regex("""\b([1-7]|[A-HJ-NP-Z]|SIR)\s+[Tt]rain\b""").find(text)?.groupValues?.get(1)?.uppercase()
        for (line in text.lines().map { it.trim() }) {
            val m = fromToViaRegex.find(line) ?: continue
            val originToken = m.groupValues[1].trim()
            val destToken = m.groupValues[2].replace(shortUrlRegex, "").trim()
            val routeToken = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.uppercase() ?: headerRoute

            val originId = resolveStationToken(originToken, routeToken)
            val destId = resolveStationToken(destToken, routeToken)
            if (originId != null && destId != null && originId != destId) {
                val resolved = graphEngine.resolveOriginToDestinationAuto(
                    originStationId = originId,
                    destinationStationId = destId,
                    preferredRouteId = routeToken,
                    timestampEpochMs = timestampEpochMs
                )
                if (resolved.legs.isNotEmpty()) {
                    return SubwayDao.buildCompleteRideRecord(
                        rideId = "share_txt_${timestampEpochMs}",
                        startTimeEpochMs = timestampEpochMs,
                        endTimeEpochMs = 0L,
                        source = source,
                        resolved = resolved,
                        graphEngine = graphEngine
                    )
                }
            }
        }
        return null
    }

    private suspend fun resolveRedirectLocation(shortUrl: String): String? {
        return runCatching {
            val response = httpClient.get(shortUrl)
            response.headers[HttpHeaders.Location]
        }.getOrNull()
    }

    private fun resolveStationToken(token: String, preferredRoute: String? = null): String? {
        val trimmed = token.trim()
        // 1. Direct station ID match
        graphEngine.getStation(trimmed)?.let { return it.id }

        // 2. Coordinate pair "40.7347,-73.9899"
        val parts = trimmed.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null) {
                return graphEngine.findNearestStation(lat, lng, preferredRouteId = preferredRoute)?.id
            }
        }

        // 3. Station name search preferring station serving preferredRoute
        val matches = graphEngine.searchStations(trimmed)
        if (matches.isEmpty()) return null
        if (preferredRoute != null) {
            matches.firstOrNull { preferredRoute in it.daytimeRoutes }?.let { return it.id }
        }
        return matches.first().id
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx <= 0) null else pair.substring(0, idx) to decodeUrlToken(pair.substring(idx + 1))
        }.toMap()
    }

    private fun decodeUrlToken(raw: String): String {
        return raw.replace("+", " ")
            .replace("%20", " ")
            .replace("%2C", ",")
            .replace("%E2%80%93", "-")
            .replace("%2D", "-")
    }
}
