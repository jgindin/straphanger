package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.ArrivalBoard
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.diagnostics.DiagnosticsLog
import com.straphanger.app.diagnostics.DiagnosticsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

class CommuteAlertsTest {
  private val commute = AlertsHarness.commute()

  @Test
  fun firePostsAndSchedulesNext() = runTest {
    val h = AlertsHarness(listOf(commute))
    h.alerts.fire("c1")
    assertEquals(listOf("c1" to nyMs(2027, 3, 9, 7, 45)), h.scheduler.scheduled)
    assertEquals(
      listOf("c1" to CommuteAlertText("A Southbound · 168 St", "3 min", null, listOf("3 min"))),
      h.posted,
    )
    assertEquals(listOf(CommuteTap("614", "A", Direction.SOUTH)), h.taps)
    assertEquals(listOf("A", "AX"), h.fetched)
  }

  @Test
  fun aLateFireIsSkippedAndTheNextIsScheduled() = runTest {
    val h = AlertsHarness(listOf(commute), nowMs = nyMs(2027, 3, 8, 8, 1))
    h.alerts.fire("c1")
    assertEquals(emptyList(), h.posted)
    assertEquals(listOf("c1" to nyMs(2027, 3, 9, 7, 45)), h.scheduler.scheduled)
    // Exactly 15 minutes late still posts.
    val onTime = AlertsHarness(listOf(commute), nowMs = nyMs(2027, 3, 8, 8, 0))
    onTime.alerts.fire("c1")
    assertEquals(1, onTime.posted.size)
  }

  @Test
  fun aLateFireIsLogged() = runTest {
    val warnings = mutableListOf<String>()
    val previous = DiagnosticsLog.sink
    DiagnosticsLog.sink = DiagnosticsSink { _, message, _ -> warnings += message }
    try {
      AlertsHarness(listOf(commute), nowMs = nyMs(2027, 3, 8, 8, 1)).alerts.fire("c1")
      assertEquals(listOf("A commute alarm fired 16 min late; skipped"), warnings)
    } finally {
      DiagnosticsLog.sink = previous
    }
  }

  @Test
  fun aFailedTrainFeedPostsCouldNotGetTrainTimes() = runTest {
    // The alerts feed was read but the train feed was not: no arrivals, and partial.
    val h =
      AlertsHarness(
        listOf(commute),
        fetch = { RealtimeSnapshot(ArrivalBoard(emptyList()), emptyList(), 0L, partial = true) },
      )
    h.alerts.fire("c1")
    assertEquals(listOf("Couldn't get train times"), h.posted.map { it.second.text })
  }

  @Test
  fun aHangingFetchPostsCouldNotGetTrainTimes() = runTest {
    val h = AlertsHarness(listOf(commute), fetch = { awaitCancellation() })
    h.alerts.fire("c1")
    assertEquals(listOf("Couldn't get train times"), h.posted.map { it.second.text })
    assertEquals(COMMUTE_FETCH_TIMEOUT_MS, testScheduler.currentTime)
  }

  @Test
  fun aThrowingFetchPostsCouldNotGetTrainTimes() = runTest {
    val h = AlertsHarness(listOf(commute), fetch = { error("MTA down") })
    h.alerts.fire("c1")
    assertEquals(listOf("Couldn't get train times"), h.posted.map { it.second.text })
    assertEquals(1, h.scheduler.scheduled.size)
  }

  @Test
  fun disabledOrDeletedCommuteCancelsAndPostsNothing() = runTest {
    val h = AlertsHarness(listOf(AlertsHarness.commute(enabled = false)))
    h.alerts.fire("c1")
    h.alerts.fire("gone")
    assertEquals(listOf("c1", "gone"), h.scheduler.cancelled)
    assertEquals(emptyList(), h.scheduler.scheduled)
    assertEquals(emptyList(), h.posted)
  }

  @Test
  fun missingStationPostsNothing() = runTest {
    val h = AlertsHarness(listOf(AlertsHarness.commute(complexId = "999")))
    h.alerts.fire("c1")
    assertEquals(emptyList(), h.posted)
    // Like rescheduleAll, a commute whose station is gone keeps no alarm.
    assertEquals(listOf("c1"), h.scheduler.cancelled)
  }

  @Test
  fun rescheduleAllSchedulesEnabledAndCancelsOthers() = runTest {
    val commutes =
      listOf(
        AlertsHarness.commute("on"),
        AlertsHarness.commute("off", enabled = false),
        AlertsHarness.commute("noDays").copy(days = emptySet()),
        AlertsHarness.commute("gone", complexId = "999"),
      )
    val h = AlertsHarness(commutes, nowMs = nyMs(2027, 3, 8, 7, 0))
    h.alerts.rescheduleAll()
    assertEquals(listOf("on" to nyMs(2027, 3, 8, 7, 45)), h.scheduler.scheduled)
    assertEquals(listOf("off", "noDays", "gone"), h.scheduler.cancelled)

    // Before the network loads, every station is assumed to still exist.
    val unloaded = AlertsHarness(commutes, nowMs = nyMs(2027, 3, 8, 7, 0), complexes = null)
    unloaded.alerts.rescheduleAll()
    assertEquals(listOf("on", "gone"), unloaded.scheduler.scheduled.map { it.first })
  }

  @Test
  fun saveCancelsRemovedCommutes() = runTest {
    val h = AlertsHarness(listOf(AlertsHarness.commute("keep"), AlertsHarness.commute("drop")))
    val added = AlertsHarness.commute("new")
    h.alerts.save(listOf(AlertsHarness.commute("keep"), added))
    assertEquals(listOf("keep", "new"), h.store.read().map { it.id })
    assertEquals(listOf("drop"), h.scheduler.cancelled)
    assertEquals(listOf("keep", "new"), h.scheduler.scheduled.map { it.first })
  }
}
