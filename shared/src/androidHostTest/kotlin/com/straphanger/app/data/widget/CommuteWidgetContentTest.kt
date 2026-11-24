package com.straphanger.app.data.widget

import com.straphanger.app.data.commute.AlertsHarness
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.commute.nyMs
import com.straphanger.app.data.realtime.Direction
import kotlin.test.Test
import kotlin.test.assertEquals

/** 2027-03-08 is a Monday; the A commute's window is weekdays 7:45–8:30. */
class CommuteWidgetContentTest {
  private val tap = CommuteTap("614", "A", Direction.SOUTH)
  private val title = "A Southbound · 168 St"

  @Test
  fun showsFreshTimesWithUpdatedTime() {
    val times = mapOf("c1" to fresh(nyMs(2027, 3, 8, 7, 52)))
    val content = content(listOf("c1"), times = times, nowMs = nyMs(2027, 3, 8, 7, 55))
    assertEquals(
      listOf(WidgetRow.Times("c1", title, tap, "3 min · 9 min", "Delays", "Updated 7:52")),
      content.rows,
    )
  }

  @Test
  fun timesOlderThanFifteenMinutesShowTapToRefresh() {
    val times = mapOf("c1" to fresh(nyMs(2027, 3, 8, 7, 52)))
    // Exactly 15 minutes old still shows; a minute more does not.
    val atLimit = content(listOf("c1"), times = times, nowMs = nyMs(2027, 3, 8, 8, 7))
    assertEquals(listOf("Times"), atLimit.rows.map { it::class.simpleName })
    val stale = content(listOf("c1"), times = times, nowMs = nyMs(2027, 3, 8, 8, 8))
    assertEquals(listOf(WidgetRow.TapToRefresh("c1", title, tap)), stale.rows)
  }

  @Test
  fun outsideEveryWindowShowsTheNextCommuteAsTapToRefresh() {
    val commutes =
      listOf(
        windowed("evening", 17, 30, 18, 30, routeId = "1"),
        windowed("morning", 7, 45, 8, 30),
      )
    val content =
      content(listOf("evening", "morning"), commutes, nowMs = nyMs(2027, 3, 8, 12, 0))
    assertEquals(
      listOf(
        WidgetRow.TapToRefresh(
          "evening",
          "1 Southbound · 168 St",
          CommuteTap("614", "1", Direction.SOUTH),
        )
      ),
      content.rows,
    )
  }

  @Test
  fun outsideAWindowFreshTappedTimesStillShow() {
    val times = mapOf("c1" to fresh(nyMs(2027, 3, 8, 11, 58)))
    val content = content(listOf("c1"), times = times, nowMs = nyMs(2027, 3, 8, 12, 0))
    assertEquals(listOf("Updated 11:58"), content.rows.map { (it as WidgetRow.Times).updatedText })
  }

  @Test
  fun aFailureKeepsFreshTimes() {
    val times =
      mapOf("c1" to fresh(nyMs(2027, 3, 8, 7, 52)).copy(failedAtMs = nyMs(2027, 3, 8, 7, 54)))
    val content = content(listOf("c1"), times = times, nowMs = nyMs(2027, 3, 8, 7, 55))
    assertEquals(listOf("Updated 7:52"), content.rows.map { (it as WidgetRow.Times).updatedText })
  }

  @Test
  fun aFailureWithNoFreshTimesShowsFailed() {
    val now = nyMs(2027, 3, 8, 8, 20)
    val oldTimes = fresh(nyMs(2027, 3, 8, 7, 52))
    val failed = mapOf("c1" to oldTimes.copy(failedAtMs = nyMs(2027, 3, 8, 8, 19)))
    assertEquals(
      listOf(WidgetRow.Failed("c1", title, tap)),
      content(listOf("c1"), times = failed, nowMs = now).rows,
    )
    val neverFetched = mapOf("c1" to CommuteTimes(failedAtMs = nyMs(2027, 3, 8, 8, 19)))
    assertEquals(
      listOf(WidgetRow.Failed("c1", title, tap)),
      content(listOf("c1"), times = neverFetched, nowMs = now).rows,
    )
    // A failure older than the times it followed, or one that is itself old, is not shown.
    val oldFailure = mapOf("c1" to oldTimes.copy(failedAtMs = nyMs(2027, 3, 8, 7, 50)))
    assertEquals(
      listOf(WidgetRow.TapToRefresh("c1", title, tap)),
      content(listOf("c1"), times = oldFailure, nowMs = now).rows,
    )
    val staleFailure = mapOf("c1" to CommuteTimes(failedAtMs = nyMs(2027, 3, 8, 8, 0)))
    assertEquals(
      listOf(WidgetRow.TapToRefresh("c1", title, tap)),
      content(listOf("c1"), times = staleFailure, nowMs = now).rows,
    )
  }

  @Test
  fun twoActiveWindowsShowBothInStartOrder() {
    val commutes = listOf(windowed("late", 8, 0, 9, 0), windowed("early", 7, 30, 8, 30))
    val content = content(listOf("late", "early"), commutes, nowMs = nyMs(2027, 3, 8, 8, 10))
    assertEquals(listOf("early", "late"), content.rows.map { it.commuteId })
  }

  @Test
  fun threeActiveShowTwo() {
    val commutes =
      listOf(
        windowed("c", 8, 0, 9, 0),
        windowed("a", 7, 0, 9, 0),
        windowed("b", 7, 30, 9, 0),
      )
    val content = content(listOf("c", "a", "b"), commutes, nowMs = nyMs(2027, 3, 8, 8, 10))
    assertEquals(listOf("a", "b"), content.rows.map { it.commuteId })
  }

  @Test
  fun noUsableCommutesGivesNoRows() {
    val now = nyMs(2027, 3, 8, 8, 0)
    assertEquals(emptyList(), content(emptyList(), nowMs = now).rows)
    assertEquals(emptyList(), content(listOf("gone"), nowMs = now).rows)
    val noEnd = listOf(AlertsHarness.commute("c1"))
    assertEquals(emptyList(), content(listOf("c1"), noEnd, nowMs = now).rows)
  }

  @Test
  fun aMissingStationShowsStationGone() {
    val gone = listOf(windowed("c1", 7, 45, 8, 30, complexId = "999"))
    val now = nyMs(2027, 3, 8, 8, 0)
    assertEquals(
      listOf(
        WidgetRow.StationGone("c1", "A Southbound", CommuteTap("999", "A", Direction.SOUTH))
      ),
      content(listOf("c1"), gone, nowMs = now).rows,
    )
    // Before the network loads, the title has no station and the row is not "gone".
    assertEquals(
      listOf(WidgetRow.TapToRefresh("c1", "A Southbound", tap)),
      content(listOf("c1"), complexes = null, nowMs = now).rows,
    )
  }
}
