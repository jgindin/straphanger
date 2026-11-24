package com.straphanger.app.data.widget

import com.straphanger.app.data.commute.nyMs
import com.straphanger.app.data.realtime.ArrivalBoard
import com.straphanger.app.data.realtime.RealtimeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/** How widgets are placed, kept scheduled and removed; the window is weekdays 7:45–8:30. */
class CommuteWidgetsLifecycleTest {
  private val now = nyMs(2027, 3, 8, 8, 0)

  @Test
  fun configureSavesSchedulesAndRedraws() = runTest {
    val h = WidgetsHarness(nowMs = nyMs(2027, 3, 8, 9, 0))
    h.widgets.configure(1, listOf("c1"))
    assertEquals(listOf("c1"), h.widgets.commuteIds(1))
    assertEquals(listOf(1 to nyMs(2027, 3, 9, 7, 45)), h.scheduler.refreshes)
    assertEquals(listOf(1), h.updated)
    // A widget configured during a window refreshes at once.
    val during = WidgetsHarness()
    during.widgets.configure(1, listOf("c1"))
    assertEquals(listOf(1 to now), during.scheduler.refreshes)
  }

  @Test
  fun aDeletedCommuteDropsOffAndItsChainStops() = runTest {
    val h = WidgetsHarness()
    h.store.setWidget(1, listOf("c1"))
    h.commuteStore.write(emptyList())
    h.widgets.onRefreshAlarm(1)
    assertEquals(listOf(1), h.scheduler.cancelled)
    assertEquals(emptyList(), h.fetched)
    h.widgets.rescheduleAll()
    assertEquals(emptyList(), h.widgets.commuteIds(1))
    assertEquals(listOf(1, 1), h.scheduler.cancelled)
    assertEquals(emptyList(), h.widgets.content(1).rows)
  }

  @Test
  fun staleTimesShowTapToRefreshAndTheNextScreenOnAlarmFetches() = runTest {
    val h = WidgetsHarness(screenOn = false)
    h.store.setWidget(1, listOf("c1"))
    h.store.setTimes("c1", fresh(now - 20 * 60_000))
    assertIs<WidgetRow.TapToRefresh>(h.widgets.content(1).rows.single())
    h.widgets.onRefreshAlarm(1)
    assertEquals(emptyList(), h.fetched)
    h.screenOn = true
    h.clock.nowMs = now + WIDGET_SCREEN_OFF_CHECK_MS
    h.widgets.onRefreshAlarm(1)
    assertEquals(1, h.fetched.size)
    assertIs<WidgetRow.Times>(h.widgets.content(1).rows.single())
  }

  @Test
  fun rescheduleAllRefreshesActiveWidgetsAtOnce() = runTest {
    val evening = windowed("pm", 17, 30, 18, 30)
    val h = WidgetsHarness(commutes = listOf(windowed("c1", 7, 45, 8, 30), evening))
    h.store.setWidget(1, listOf("c1"))
    h.store.setWidget(2, listOf("pm"))
    h.store.setWidget(3, emptyList())
    h.widgets.rescheduleAll()
    assertEquals(
      listOf(1 to now, 2 to nyMs(2027, 3, 8, 17, 30)),
      h.scheduler.refreshes.sortedBy { it.first },
    )
    assertEquals(listOf(3), h.scheduler.cancelled)
    assertEquals(listOf(1, 2, 3), h.updated.sorted())
    // It schedules rather than fetches; the alarm does the fetching.
    assertEquals(emptyList(), h.fetched)
  }

  @Test
  fun removedWidgetsAreForgottenAndCancelled() = runTest {
    val h = WidgetsHarness()
    h.store.setWidget(1, listOf("c1"))
    h.store.setWidget(2, listOf("c1"))
    h.store.setWidget(3, listOf("c1"))
    h.widgets.removed(listOf(1, 3))
    assertEquals(setOf(2), h.store.widgets().keys)
    assertEquals(listOf(1, 3), h.scheduler.cancelled)
  }

  @Test
  fun aFailedTrainFeedKeepsTheLastTimes() = runTest {
    // The alerts feed was read but the train feed was not: no arrivals, and partial.
    val partial = RealtimeSnapshot(ArrivalBoard(emptyList()), emptyList(), 0L, partial = true)
    val h = WidgetsHarness(fetch = { partial })
    h.store.setWidget(1, listOf("c1"))
    val earlier = fresh(now - 60_000)
    h.store.setTimes("c1", earlier)
    h.widgets.tapRefresh(1)
    assertEquals(earlier.copy(failedAtMs = now), h.store.times()["c1"])
  }
}
