package com.straphanger.app.data.widget

import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.commute.commuteTimeText
import com.straphanger.app.data.commute.hasWindow
import com.straphanger.app.data.commute.nextWindowStartMs
import com.straphanger.app.data.commute.windowAt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The most commutes a widget shows at once, one under the other. */
private const val MAX_ROWS = 2

/**
 * One commute on a widget: [tap] names its line and direction, [station] its station (null when not
 * known), and tapping the row opens [tap].
 */
sealed interface WidgetRow {
  val commuteId: String
  val station: String?
  val tap: CommuteTap

  /**
   * Fresh times: each departure in [departures] ("3 min"), or, when there are none, [text] ("No A
   * trains listed southbound right now"); the line's [alertLine]; and "Updated 7:52" as
   * [updatedText].
   */
  data class Times(
    override val commuteId: String,
    override val station: String?,
    override val tap: CommuteTap,
    val departures: List<String>,
    val text: String,
    val alertLine: String?,
    val updatedText: String,
  ) : WidgetRow

  /** No fresh times: "Tap to refresh". */
  data class TapToRefresh(
    override val commuteId: String,
    override val station: String?,
    override val tap: CommuteTap,
  ) : WidgetRow

  /** No fresh times, and the last fetch failed: "Couldn't get train times" with ⟳. */
  data class Failed(
    override val commuteId: String,
    override val station: String?,
    override val tap: CommuteTap,
  ) : WidgetRow

  /** The commute's station is no longer in the network: "Station no longer listed". */
  data class StationGone(
    override val commuteId: String,
    override val station: String?,
    override val tap: CommuteTap,
  ) : WidgetRow
}

/** What a widget shows; no rows means "Tap to choose commutes". */
data class CommuteWidgetContent(val rows: List<WidgetRow>)

/**
 * What a widget showing [commuteIds] says at [nowMs]: its commutes in an active window, ordered by
 * window start and at most [MAX_ROWS], or else the one whose window starts next. Unknown ids and
 * commutes without an end time are ignored. [complexes] is null when the network is not loaded.
 * [use24Hour] picks "7:52" or "7:52 AM" for the time the departures were fetched.
 */
fun commuteWidgetContent(
  commuteIds: List<String>,
  commutes: List<Commute>,
  complexes: List<StationComplex>?,
  times: Map<String, CommuteTimes>,
  nowMs: Long,
  zone: TimeZone,
  use24Hour: Boolean,
): CommuteWidgetContent {
  val shown = shownCommutes(widgetCommutes(commuteIds, commutes), nowMs, zone)
  return CommuteWidgetContent(
    shown.map { row(it, complexes, times[it.id], nowMs, zone, use24Hour) },
  )
}

/** The commutes behind [commuteIds] that can show on a widget, in the widget's order. */
internal fun widgetCommutes(commuteIds: List<String>, commutes: List<Commute>): List<Commute> {
  val byId = commutes.associateBy { it.id }
  return commuteIds.distinct().mapNotNull { byId[it] }.filter { it.hasWindow }
}

/** Those of [commutes] in a window at [nowMs], by window start, at most [MAX_ROWS]. */
internal fun activeCommutes(commutes: List<Commute>, nowMs: Long, zone: TimeZone): List<Commute> =
  commutes
    .mapNotNull { commute -> commute.windowAt(nowMs, zone)?.let { commute to it.first } }
    .sortedBy { it.second }
    .take(MAX_ROWS)
    .map { it.first }

/** Those of [usable] a widget shows at [nowMs]: the active ones, else the next one. */
internal fun shownCommutes(usable: List<Commute>, nowMs: Long, zone: TimeZone): List<Commute> =
  activeCommutes(usable, nowMs, zone).ifEmpty { listOfNotNull(nextCommute(usable, nowMs, zone)) }

/** The one of [commutes] whose window starts soonest after [nowMs], if any. */
internal fun nextCommute(commutes: List<Commute>, nowMs: Long, zone: TimeZone): Commute? =
  commutes
    .mapNotNull { commute -> commute.nextWindowStartMs(nowMs, zone)?.let { commute to it } }
    .minByOrNull { it.second }
    ?.first

private fun row(
  commute: Commute,
  complexes: List<StationComplex>?,
  times: CommuteTimes?,
  nowMs: Long,
  zone: TimeZone,
  use24Hour: Boolean,
): WidgetRow {
  val complex = complexes?.firstOrNull { it.complexId == commute.complexId }
  val station = complex?.name
  val tap = CommuteTap(commute.complexId, commute.routeId, commute.direction)
  val text = times?.text
  val fetchedAt = times?.fetchedAtMs
  val failedAt = times?.failedAtMs
  return when {
    complexes != null && complex == null -> WidgetRow.StationGone(commute.id, station, tap)
    text != null && fetchedAt != null && nowMs - fetchedAt <= WIDGET_STALE_MS ->
      WidgetRow.Times(
        commute.id,
        station,
        tap,
        // Times stored by 0.25 have no departures; [text] holds them instead.
        times.departures,
        text,
        times.alertLine,
        updatedText(fetchedAt, zone, use24Hour),
      )
    failedAt != null &&
      nowMs - failedAt <= WIDGET_STALE_MS &&
      failedAt > (fetchedAt ?: Long.MIN_VALUE) -> WidgetRow.Failed(commute.id, station, tap)
    else -> WidgetRow.TapToRefresh(commute.id, station, tap)
  }
}

/** "Updated 7:52", or "Updated 7:52 AM" in 12-hour time, in [zone]. */
private fun updatedText(fetchedAtMs: Long, zone: TimeZone, use24Hour: Boolean): String {
  val local = Instant.fromEpochMilliseconds(fetchedAtMs).toLocalDateTime(zone)
  return "Updated " + commuteTimeText(local.hour, local.minute, use24Hour)
}
