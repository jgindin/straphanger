package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.AlertKind
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.realtime.RealtimeSnapshot

/**
 * What a commute's notification says: [title] names the train, [text] its next departures, and
 * [alertLine] the header of a service alert on that line, when there is one.
 */
data class CommuteAlertText(val title: String, val text: String, val alertLine: String?)

private const val MAX_DEPARTURES = 3

private const val FETCH_FAILED_TEXT = "Couldn't get train times"

/** Alerts that matter most to someone about to leave come first. */
private val ALERT_PREFERENCE = listOf(AlertKind.DELAY, AlertKind.OTHER, AlertKind.PLANNED)

/** "now" for 0, else "7 min". */
fun minutesText(minutes: Int): String = if (minutes == 0) "now" else "$minutes min"

/** True when [routeId] is the commute's line or that line's express variant. */
private fun Commute.matchesLine(routeId: String): Boolean =
  routeId == this.routeId || routeId == this.routeId + "X"

/**
 * The notification for [commute] at [complex]. A null [snapshot] means the fetch failed or timed
 * out; a partial one with no departures for the line is taken as a failed train feed.
 */
fun commuteAlertText(
  commute: Commute,
  complex: StationComplex,
  snapshot: RealtimeSnapshot?,
  nowEpochSeconds: Long,
): CommuteAlertText {
  val directionName = if (commute.direction == Direction.NORTH) "Northbound" else "Southbound"
  val title = "${commute.routeId} $directionName · ${complex.name}"
  if (snapshot == null) return CommuteAlertText(title, FETCH_FAILED_TEXT, null)
  val departures =
    complex.stationIds
      .flatMap { snapshot.arrivals.arrivalsAt(it, nowEpochSeconds, perLine = MAX_DEPARTURES) }
      .filter { it.direction == commute.direction && commute.matchesLine(it.routeId) }
      .sortedBy { it.epochSeconds }
      .take(MAX_DEPARTURES)
  val text =
    if (departures.isEmpty() && snapshot.partial) {
      FETCH_FAILED_TEXT
    } else if (departures.isEmpty()) {
      "No ${commute.routeId} trains listed ${directionName.lowercase()} right now"
    } else {
      // "3, 9, 14 min": the unit once, on the last; "now" wherever a train is arriving.
      departures
        .map { it.minutesFrom(nowEpochSeconds) }
        .mapIndexed { i, m -> if (m == 0 || i == departures.lastIndex) minutesText(m) else "$m" }
        .joinToString(", ")
    }
  val alertLine =
    snapshot.alerts
      .filter { alert -> alert.routes.any(commute::matchesLine) }
      .minByOrNull { ALERT_PREFERENCE.indexOf(it.kind).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
      ?.header
  return CommuteAlertText(title, text, alertLine)
}
