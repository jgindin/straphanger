package com.straphanger.app.data.widget

import com.straphanger.app.data.commute.COMMUTE_FETCH_TIMEOUT_MS
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.FETCH_FAILED_TEXT
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.commute.commuteAlertText
import com.straphanger.app.data.commute.fetchCommute
import com.straphanger.app.data.realtime.RealtimeSnapshot
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

private const val MS_PER_SECOND = 1_000L

/**
 * Fetches commutes' times for [CommuteWidgets] into [store], and sets the stale redraw that clears
 * them once they are old. A failure only records when it happened; the last good times stay.
 */
internal class CommuteTimesFetcher(
  private val store: CommuteWidgetStore,
  private val complexes: suspend () -> List<StationComplex>?,
  private val fetch: suspend (Collection<String>) -> RealtimeSnapshot,
  private val scheduler: WidgetRefreshScheduler,
  private val clock: Clock,
) {
  private val nowMs: Long
    get() = clock.now().toEpochMilliseconds()

  /**
   * Fetches every one of [commutes] at once, while the network loads; each fetch and the load have
   * [COMMUTE_FETCH_TIMEOUT_MS] from the start.
   */
  suspend fun fetch(commutes: List<Commute>) {
    if (commutes.isEmpty()) return
    coroutineScope {
      val all = async { withTimeoutOrNull(COMMUTE_FETCH_TIMEOUT_MS) { complexes() } }
      val snapshots = commutes.map { async { fetchCommute(it, fetch) } }.awaitAll()
      val loaded = all.await()
      commutes.zip(snapshots).forEach { (commute, snapshot) -> record(commute, loaded, snapshot) }
    }
  }

  /**
   * Stores [commute]'s times from [snapshot], or the failure when there are none. A commute whose
   * station is no longer listed stores nothing; without the network there is no text to store.
   */
  private fun record(
    commute: Commute,
    complexes: List<StationComplex>?,
    snapshot: RealtimeSnapshot?,
  ) {
    val complex = complexes?.firstOrNull { it.complexId == commute.complexId }
    if (complexes != null && complex == null) return
    val now = nowMs
    val text =
      if (complex != null && snapshot != null) {
        commuteAlertText(commute, complex, snapshot, now / MS_PER_SECOND)
      } else {
        null
      }
    val times =
      if (text == null || text.text == FETCH_FAILED_TEXT) {
        (store.times()[commute.id] ?: CommuteTimes()).copy(failedAtMs = now)
      } else {
        CommuteTimes(text.text, text.alertLine, fetchedAtMs = now)
      }
    store.setTimes(commute.id, times)
  }

  /**
   * Sets [widgetId]'s stale redraw for when the newest times of [shown] grow old, unless they
   * already have.
   */
  fun scheduleStaleRedraw(widgetId: Int, shown: List<Commute>) {
    val times = store.times()
    val newest = shown.mapNotNull { times[it.id]?.fetchedAtMs }.maxOrNull() ?: return
    val at = newest + WIDGET_STALE_MS
    if (at > nowMs) scheduler.scheduleStaleRedraw(widgetId, at)
  }
}
