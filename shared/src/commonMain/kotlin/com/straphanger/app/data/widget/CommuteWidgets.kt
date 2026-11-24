package com.straphanger.app.data.widget

import com.straphanger.app.data.commute.COMMUTE_FETCH_TIMEOUT_MS
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.CommuteStore
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.commute.nextWindowStartMs
import com.straphanger.app.data.realtime.RealtimeSnapshot
import kotlin.time.Clock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone

/** The next refresh after a fetch, while the screen is on. */
const val WIDGET_REFRESH_MS = 2 * 60_000L

/** The next check when a refresh alarm finds the screen off; it fetches nothing. */
const val WIDGET_SCREEN_OFF_CHECK_MS = 60_000L

/** Times older than this show as "Tap to refresh". */
const val WIDGET_STALE_MS = 15 * 60_000L

/** An automatic refresh skips a commute fetched this recently, as by another widget. */
const val WIDGET_FETCH_REUSE_MS = 60_000L

/**
 * Keeps every placed widget's commutes, times and alarms. During a commute's window its widgets
 * fetch every [WIDGET_REFRESH_MS] while [screenOn]; outside every window they fetch only when
 * tapped. [complexes] loads the station complexes; null means it could not be loaded.
 */
class CommuteWidgets(
  private val store: CommuteWidgetStore,
  private val commutes: CommuteStore,
  private val complexes: suspend () -> List<StationComplex>?,
  fetch: suspend (Collection<String>) -> RealtimeSnapshot,
  private val scheduler: WidgetRefreshScheduler,
  private val renderer: WidgetRenderer,
  private val screenOn: () -> Boolean,
  private val clock: Clock = Clock.System,
  private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
) {
  private val fetcher = CommuteTimesFetcher(store, complexes, fetch, scheduler, clock)

  private val nowMs: Long
    get() = clock.now().toEpochMilliseconds()

  /** What [widgetId] shows now; waits up to [COMMUTE_FETCH_TIMEOUT_MS] for the network. */
  suspend fun content(widgetId: Int): CommuteWidgetContent {
    val all = withTimeoutOrNull(COMMUTE_FETCH_TIMEOUT_MS) { complexes() }
    return commuteWidgetContent(
      commuteIds(widgetId),
      commutes.read(),
      all,
      store.times(),
      nowMs,
      zone(),
    )
  }

  /** The commutes ticked for [widgetId], in order; empty for an unknown widget. */
  fun commuteIds(widgetId: Int): List<String> = store.widgets()[widgetId].orEmpty()

  /** Saves the commutes [widgetId] shows, then sets its alarms and redraws it. */
  suspend fun configure(widgetId: Int, commuteIds: List<String>) {
    store.setWidget(widgetId, commuteIds)
    reschedule(widgetId)
    renderer.update(widgetId)
  }

  /** Forgets [widgetIds] and cancels their alarms. */
  fun removed(widgetIds: Collection<Int>) {
    store.removeWidgets(widgetIds)
    widgetIds.forEach(scheduler::cancel)
  }

  /**
   * The refresh alarm: during a window with the screen on, sets the next refresh and then fetches
   * the shown commutes not fetched in the last [WIDGET_FETCH_REUSE_MS]; with the screen off, only
   * checks again in [WIDGET_SCREEN_OFF_CHECK_MS]; outside every window, waits for the next one. The
   * next refresh is set first because this alarm has already fired: a call cancelled mid-fetch, as
   * by the caller's time cap, must not end the chain.
   */
  suspend fun onRefreshAlarm(widgetId: Int) {
    val usable = usableCommutes(widgetId)
    val now = nowMs
    val tz = zone()
    val active = activeCommutes(usable, now, tz)
    when {
      active.isEmpty() -> scheduleIdle(widgetId, usable, now, tz)
      !screenOn() -> scheduler.scheduleRefresh(widgetId, now + WIDGET_SCREEN_OFF_CHECK_MS)
      else -> {
        scheduler.scheduleRefresh(widgetId, now + WIDGET_REFRESH_MS)
        val times = store.times()
        fetcher.fetch(
          active.filter { commute ->
            val fetchedAt = times[commute.id]?.fetchedAtMs
            fetchedAt == null || now - fetchedAt >= WIDGET_FETCH_REUSE_MS
          },
        )
        fetcher.scheduleStaleRedraw(widgetId, active)
      }
    }
    renderer.update(widgetId)
  }

  /** The stale redraw: redraws old times as "Tap to refresh", then waits for the next to age. */
  suspend fun onStaleAlarm(widgetId: Int) {
    renderer.update(widgetId)
    fetcher.scheduleStaleRedraw(widgetId, shownCommutes(usableCommutes(widgetId), nowMs, zone()))
  }

  /**
   * ⟳ or "Tap to refresh": fetches the shown commutes even if they are recent, and redraws. During
   * a window it first sets the next refresh, so a call cancelled mid-fetch still keeps the chain.
   */
  suspend fun tapRefresh(widgetId: Int) {
    val usable = usableCommutes(widgetId)
    val now = nowMs
    val tz = zone()
    val active = activeCommutes(usable, now, tz)
    if (active.isNotEmpty()) scheduler.scheduleRefresh(widgetId, now + WIDGET_REFRESH_MS)
    val shown = active.ifEmpty { listOfNotNull(nextCommute(usable, now, tz)) }
    fetcher.fetch(shown)
    fetcher.scheduleStaleRedraw(widgetId, shown)
    renderer.update(widgetId)
  }

  /**
   * Drops deleted commutes from every widget, sets each widget's alarms again and redraws it. A
   * widget in a window refreshes at once.
   */
  suspend fun rescheduleAll() {
    val known = commutes.read().mapTo(HashSet()) { it.id }
    store.widgets().forEach { (widgetId, ids) ->
      val kept = ids.filter { it in known }
      if (kept != ids) store.setWidget(widgetId, kept)
      reschedule(widgetId)
      renderer.update(widgetId)
    }
  }

  /**
   * Refreshes [widgetId] now if one of its windows is active, else waits for the next one; and sets
   * the stale redraw for the times it shows.
   */
  private fun reschedule(widgetId: Int) {
    val usable = usableCommutes(widgetId)
    val now = nowMs
    val tz = zone()
    if (activeCommutes(usable, now, tz).isNotEmpty()) {
      scheduler.scheduleRefresh(widgetId, now)
    } else {
      scheduleIdle(widgetId, usable, now, tz)
    }
    fetcher.scheduleStaleRedraw(widgetId, shownCommutes(usable, now, tz))
  }

  /** Sets the refresh for the earliest next window start of [usable], or cancels without one. */
  private fun scheduleIdle(widgetId: Int, usable: List<Commute>, now: Long, tz: TimeZone) {
    val next = usable.mapNotNull { it.nextWindowStartMs(now, tz) }.minOrNull()
    if (next == null) scheduler.cancel(widgetId) else scheduler.scheduleRefresh(widgetId, next)
  }

  private fun usableCommutes(widgetId: Int): List<Commute> =
    widgetCommutes(commuteIds(widgetId), commutes.read())
}
