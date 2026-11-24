package com.straphanger.app.data.widget

import com.straphanger.app.data.commute.COMMUTE_FETCH_TIMEOUT_MS
import com.straphanger.app.data.commute.nyMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/** How a widget's refresh alarm and its ⟳ fetch; the window is weekdays 7:45–8:30. */
class CommuteWidgetsTest {
  private val now = nyMs(2027, 3, 8, 8, 0)

  @Test
  fun aRefreshWithTheScreenOnFetchesAndSchedulesInTwoMinutes() = runTest {
    val h = WidgetsHarness()
    h.store.setWidget(1, listOf("c1"))
    h.widgets.onRefreshAlarm(1)
    assertEquals(listOf(listOf("A", "AX")), h.fetched)
    assertEquals(listOf(1 to now + WIDGET_REFRESH_MS), h.scheduler.refreshes)
    assertEquals(CommuteTimes("3 min", null, now, departures = listOf("3 min")), h.store.times()["c1"])
    assertEquals(listOf(1), h.updated)
    assertIs<WidgetRow.Times>(h.widgets.content(1).rows.single())
  }

  @Test
  fun aRefreshWithTheScreenOffOnlySchedulesInOneMinute() = runTest {
    val h = WidgetsHarness(screenOn = false)
    h.store.setWidget(1, listOf("c1"))
    h.widgets.onRefreshAlarm(1)
    assertEquals(emptyList(), h.fetched)
    assertEquals(listOf(1 to now + WIDGET_SCREEN_OFF_CHECK_MS), h.scheduler.refreshes)
    assertEquals(emptyList(), h.scheduler.staleRedraws)
    assertEquals(listOf(1), h.updated)
  }

  @Test
  fun outsideAWindowTheRefreshIsScheduledForTheNextStart() = runTest {
    val h = WidgetsHarness(nowMs = nyMs(2027, 3, 8, 9, 0))
    h.store.setWidget(1, listOf("c1"))
    h.widgets.onRefreshAlarm(1)
    assertEquals(emptyList(), h.fetched)
    assertEquals(listOf(1 to nyMs(2027, 3, 9, 7, 45)), h.scheduler.refreshes)
    assertEquals(listOf(1), h.updated)
  }

  @Test
  fun aSecondWidgetReusesARecentFetch() = runTest {
    val h = WidgetsHarness()
    h.store.setWidget(1, listOf("c1"))
    h.store.setWidget(2, listOf("c1"))
    h.widgets.onRefreshAlarm(1)
    h.clock.nowMs = now + 59_000
    h.widgets.onRefreshAlarm(2)
    assertEquals(1, h.fetched.size)
    assertEquals(listOf(2 to now + 59_000 + WIDGET_REFRESH_MS), h.scheduler.refreshes.drop(1))
    // A minute after the fetch it is no longer recent.
    h.clock.nowMs = now + WIDGET_FETCH_REUSE_MS
    h.widgets.onRefreshAlarm(2)
    assertEquals(2, h.fetched.size)
  }

  @Test
  fun tapRefreshAlwaysFetches() = runTest {
    // Outside the window, with times fetched a moment ago, a tap still fetches.
    val h = WidgetsHarness(nowMs = nyMs(2027, 3, 8, 12, 0))
    h.store.setWidget(1, listOf("c1"))
    h.store.setTimes("c1", fresh(nyMs(2027, 3, 8, 12, 0)))
    h.widgets.tapRefresh(1)
    assertEquals(1, h.fetched.size)
    assertEquals(listOf(1), h.updated)
    // Outside a window a tap starts no refresh chain.
    assertEquals(emptyList(), h.scheduler.refreshes)
    h.clock.nowMs = now
    h.widgets.tapRefresh(1)
    assertEquals(2, h.fetched.size)
    assertEquals(listOf(1 to now + WIDGET_REFRESH_MS), h.scheduler.refreshes)
  }

  @Test
  fun aFetchSetsTheStaleRedraw() = runTest {
    val h = WidgetsHarness()
    h.store.setWidget(1, listOf("c1"))
    h.widgets.onRefreshAlarm(1)
    assertEquals(listOf(1 to now + WIDGET_STALE_MS), h.scheduler.staleRedraws)
    h.widgets.onStaleAlarm(1)
    assertEquals(listOf(1, 1), h.updated)
    assertEquals(1, h.fetched.size)
  }

  @Test
  fun aFailedFetchKeepsTheLastTimes() = runTest {
    val h = WidgetsHarness(fetch = { error("MTA down") })
    h.store.setWidget(1, listOf("c1"))
    val earlier = fresh(now - 5 * 60_000)
    h.store.setTimes("c1", earlier)
    h.widgets.onRefreshAlarm(1)
    assertEquals(earlier.copy(failedAtMs = now), h.store.times()["c1"])
    assertEquals(listOf("Updated 7:55"), h.widgets.content(1).rows.map { it.updatedText() })
    // The stale redraw follows the times still on screen.
    assertEquals(listOf(1 to now - 5 * 60_000 + WIDGET_STALE_MS), h.scheduler.staleRedraws)
  }

  @Test
  fun aHangingFetchTimesOutAsFailed() = runTest {
    val h = WidgetsHarness(fetch = { awaitCancellation() })
    h.store.setWidget(1, listOf("c1"))
    h.widgets.tapRefresh(1)
    assertEquals(COMMUTE_FETCH_TIMEOUT_MS, testScheduler.currentTime)
    assertEquals(CommuteTimes(failedAtMs = now), h.store.times()["c1"])
    assertIs<WidgetRow.Failed>(h.widgets.content(1).rows.single())
    assertEquals(listOf(1), h.updated)
  }

  @Test
  fun aRefreshCancelledMidFetchStillSchedulesTheNext() = runTest {
    // The holder's cap cancels a call that waited for the lock; the chain must survive it.
    val h = WidgetsHarness(fetch = { awaitCancellation() })
    h.store.setWidget(1, listOf("c1"))
    withTimeoutOrNull(1_000) { h.widgets.onRefreshAlarm(1) }
    assertEquals(listOf(1 to now + WIDGET_REFRESH_MS), h.scheduler.refreshes)
    withTimeoutOrNull(1_000) { h.widgets.tapRefresh(1) }
    assertEquals(listOf(1 to now + WIDGET_REFRESH_MS), h.scheduler.refreshes.drop(1))
  }

  @Test
  fun theStaleRedrawFollowsTheOldestShownTimes() = runTest {
    val h =
      WidgetsHarness(
        commutes = listOf(windowed("c1", 7, 45, 8, 30), windowed("c2", 7, 50, 8, 30)),
        fetch = { error("MTA down") },
      )
    h.store.setWidget(1, listOf("c1", "c2"))
    h.store.setTimes("c1", fresh(now - 10 * 60_000))
    h.store.setTimes("c2", fresh(now - 30_000))
    // c1's fetch fails, keeping its old times; c2 was fetched too recently to fetch again.
    h.widgets.onRefreshAlarm(1)
    assertEquals(listOf(1 to now + 5 * 60_000), h.scheduler.staleRedraws)
    // Each redraw sets the next for times still on screen: c2's times, then c1's failure.
    h.clock.nowMs = now + 5 * 60_000
    h.widgets.onStaleAlarm(1)
    assertEquals(1 to now - 30_000 + WIDGET_STALE_MS, h.scheduler.staleRedraws.last())
    h.clock.nowMs = now - 30_000 + WIDGET_STALE_MS
    h.widgets.onStaleAlarm(1)
    assertEquals(1 to now + WIDGET_STALE_MS, h.scheduler.staleRedraws.last())
    h.clock.nowMs = now + WIDGET_STALE_MS + 1
    h.widgets.onStaleAlarm(1)
    assertEquals(3, h.scheduler.staleRedraws.size)
    assertEquals(4, h.updated.size)
  }

  private fun WidgetRow.updatedText(): String? = (this as? WidgetRow.Times)?.updatedText
}
