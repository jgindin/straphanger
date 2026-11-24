package com.straphanger.app.data.widget

import com.straphanger.app.data.commute.AlertsHarness
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.FakeClock
import com.straphanger.app.data.commute.InMemoryCommuteStore
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.commute.nyMs
import com.straphanger.app.data.realtime.Arrival
import com.straphanger.app.data.realtime.ArrivalBoard
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.realtime.RealtimeSnapshot
import kotlinx.datetime.TimeZone

internal val NY: TimeZone = TimeZone.of("America/New_York")

/** A weekday commute on the A at 168 St, shown on widgets from [hour]:[minute] to the end. */
internal fun windowed(
  id: String,
  hour: Int,
  minute: Int,
  endHour: Int,
  endMinute: Int,
  routeId: String = "A",
  complexId: String = "614",
): Commute =
  AlertsHarness.commute(id, complexId)
    .copy(routeId = routeId, hour = hour, minute = minute, endHour = endHour, endMinute = endMinute)

internal fun fresh(fetchedAtMs: Long) = CommuteTimes("3 min · 9 min", "Delays", fetchedAtMs)

internal fun content(
  commuteIds: List<String>,
  commutes: List<Commute> = listOf(windowed("c1", 7, 45, 8, 30)),
  complexes: List<StationComplex>? = listOf(AlertsHarness.COMPLEX),
  times: Map<String, CommuteTimes> = emptyMap(),
  nowMs: Long,
): CommuteWidgetContent = commuteWidgetContent(commuteIds, commutes, complexes, times, nowMs, NY)

/** Records every alarm the widgets set or cancel. */
internal class RecordingWidgetScheduler : WidgetRefreshScheduler {
  val refreshes = mutableListOf<Pair<Int, Long>>()
  val staleRedraws = mutableListOf<Pair<Int, Long>>()
  val cancelled = mutableListOf<Int>()

  override fun scheduleRefresh(widgetId: Int, atEpochMs: Long) {
    refreshes += widgetId to atEpochMs
  }

  override fun scheduleStaleRedraw(widgetId: Int, atEpochMs: Long) {
    staleRedraws += widgetId to atEpochMs
  }

  override fun cancel(widgetId: Int) {
    cancelled += widgetId
  }
}

/** [CommuteWidgets] with fakes around it, at [nowMs] (default Monday 2027-03-08 8:00). */
internal class WidgetsHarness(
  commutes: List<Commute> = listOf(windowed("c1", 7, 45, 8, 30)),
  nowMs: Long = nyMs(2027, 3, 8, 8, 0),
  var screenOn: Boolean = true,
  fetch: (suspend (Collection<String>) -> RealtimeSnapshot)? = null,
) {
  val fetched = mutableListOf<List<String>>()
  val updated = mutableListOf<Int>()
  val store = InMemoryCommuteWidgetStore()
  val commuteStore = InMemoryCommuteStore(commutes)
  val scheduler = RecordingWidgetScheduler()
  val clock = FakeClock(nowMs)
  private val fetchOrDefault: suspend (Collection<String>) -> RealtimeSnapshot =
    fetch ?: { routes -> snapshot(clock.nowMs).also { fetched += routes.toList() } }

  val widgets =
    CommuteWidgets(
      store = store,
      commutes = commuteStore,
      complexes = { listOf(AlertsHarness.COMPLEX) },
      fetch = { fetchOrDefault(it) },
      scheduler = scheduler,
      renderer = { updated += it },
      screenOn = { screenOn },
      clock = clock,
      zone = { NY },
    )

  companion object {
    /** One A train southbound at 168 St, three minutes after [nowMs]. */
    fun snapshot(nowMs: Long) =
      RealtimeSnapshot(
        ArrivalBoard(listOf(Arrival("A", "A09", Direction.SOUTH, nowMs / 1000 + 180))),
        emptyList(),
        nowMs / 1000,
        partial = false,
      )
  }
}
