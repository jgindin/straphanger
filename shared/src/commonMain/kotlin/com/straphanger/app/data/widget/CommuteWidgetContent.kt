package com.straphanger.app.data.widget

import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.commute.commuteTimeText
import com.straphanger.app.data.commute.commuteTitle
import com.straphanger.app.data.commute.hasWindow
import com.straphanger.app.data.commute.nextWindowStartMs
import com.straphanger.app.data.commute.windowAt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The most commutes a widget shows at once, one under the other. */
private const val MAX_ROWS = 2

/** One commute on a widget: [title] names it, and tapping the row opens [tap]. */
sealed interface WidgetRow {
  val commuteId: String
  val title: String
  val tap: CommuteTap

  /** Fresh departures, [text], with the line's [alertLine] and "Updated 7:52" as [updatedText]. */
  data class Times(
    override val commuteId: String,
    override val title: String,
    override val tap: CommuteTap,
    val text: String,
    val alertLine: String?,
    val updatedText: String,
  ) : WidgetRow

  /** No fresh times: "Tap to refresh". */
  data class TapToRefresh(
    override val commuteId: String,
    override val title: String,
    override val tap: CommuteTap,
  ) : WidgetRow

  /** No fresh times, and the last fetch failed: "Couldn't get train times" with ⟳. */
  data class Failed(
    override val commuteId: String,
    override val title: String,
    override val tap: CommuteTap,
  ) : WidgetRow

  /** The commute's station is no longer in the network: "Station no longer listed". */
  data class StationGone(
    override val commuteId: String,
    override val title: String,
    override val tap: CommuteTap,
  ) : WidgetRow
}

/** What a widget shows; no rows means "Tap to choose commutes". */
data class CommuteWidgetContent(val rows: List<WidgetRow>)

/**
 * What a widget showing [commuteIds] says at [nowMs]: its commutes in an active window, ordered by
 * window start and at most [MAX_ROWS], or else the one whose window starts next. Unknown ids and
 * commutes without an end time are ignored. [complexes] is null when the network is not loaded.
 */
fun commuteWidgetContent(
  commuteIds: List<String>,
  commutes: List<Commute>,
  complexes: List<StationComplex>?,
  times: Map<String, CommuteTimes>,
  nowMs: Long,
  zone: TimeZone,
): CommuteWidgetContent {
  val usable = widgetCommutes(commuteIds, commutes)
  val shown =
    activeCommutes(usable, nowMs, zone).ifEmpty { listOfNotNull(nextCommute(usable, nowMs, zone)) }
  return CommuteWidgetContent(shown.map { row(it, complexes, times[it.id], nowMs, zone) })
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
): WidgetRow {
  val complex = complexes?.firstOrNull { it.complexId == commute.complexId }
  val title = commuteTitle(commute, complex?.name)
  val tap = CommuteTap(commute.complexId, commute.routeId, commute.direction)
  val text = times?.text
  val fetchedAt = times?.fetchedAtMs
  val failedAt = times?.failedAtMs
  return when {
    complexes != null && complex == null -> WidgetRow.StationGone(commute.id, title, tap)
    text != null && fetchedAt != null && nowMs - fetchedAt <= WIDGET_STALE_MS ->
      WidgetRow.Times(commute.id, title, tap, text, times.alertLine, updatedText(fetchedAt, zone))
    failedAt != null &&
      nowMs - failedAt <= WIDGET_STALE_MS &&
      failedAt > (fetchedAt ?: Long.MIN_VALUE) -> WidgetRow.Failed(commute.id, title, tap)
    else -> WidgetRow.TapToRefresh(commute.id, title, tap)
  }
}

/** "Updated 7:52", in [zone]. */
private fun updatedText(fetchedAtMs: Long, zone: TimeZone): String {
  val local = Instant.fromEpochMilliseconds(fetchedAtMs).toLocalDateTime(zone)
  return "Updated " + commuteTimeText(local.hour, local.minute)
}
