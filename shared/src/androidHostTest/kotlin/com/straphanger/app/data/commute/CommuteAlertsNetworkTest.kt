package com.straphanger.app.data.commute

import com.straphanger.app.diagnostics.DiagnosticsLog
import com.straphanger.app.diagnostics.DiagnosticsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/** How a fired alarm copes with the station network not being loaded yet. */
class CommuteAlertsNetworkTest {
  private val commute = AlertsHarness.commute()

  @Test
  fun aNullNetworkLogsAndPostsNothing() = runTest {
    val warnings = mutableListOf<String>()
    val previous = DiagnosticsLog.sink
    DiagnosticsLog.sink = DiagnosticsSink { tag, message, _ -> warnings += "$tag: $message" }
    try {
      val h = AlertsHarness(listOf(commute), complexes = null)
      h.alerts.fire("c1")
      assertEquals(emptyList(), h.posted)
      assertEquals(emptyList(), h.scheduler.cancelled)
      assertEquals(1, h.scheduler.scheduled.size)
      assertEquals(listOf("$COMMUTE_LOG_TAG: A commute fired before the network loaded"), warnings)
    } finally {
      DiagnosticsLog.sink = previous
    }
  }

  @Test
  fun aSlowNetworkLoadIsAwaitedAndTheAlertPosts() = runTest {
    val h =
      AlertsHarness(
        listOf(commute),
        loadComplexes = {
          delay(LOAD_MS)
          listOf(AlertsHarness.COMPLEX)
        },
      )
    h.alerts.fire("c1")
    assertEquals(listOf("3 min"), h.posted.map { it.second.text })
    assertEquals(LOAD_MS, testScheduler.currentTime)
  }

  @Test
  fun aSlowLoadAndAHangingFetchShareOneLimit() = runTest {
    val h =
      AlertsHarness(
        listOf(commute),
        loadComplexes = {
          delay(SLOW_LOAD_MS)
          listOf(AlertsHarness.COMPLEX)
        },
        fetch = { awaitCancellation() },
      )
    h.alerts.fire("c1")
    assertEquals(listOf("Couldn't get train times"), h.posted.map { it.second.text })
    // The fetch ran while the network loaded, so the post comes by the limit, not load + limit.
    assertEquals(COMMUTE_FETCH_TIMEOUT_MS, testScheduler.currentTime)
  }

  @Test
  fun aHangingLoadGivesUpAtTheLimit() = runTest {
    val h = AlertsHarness(listOf(commute), loadComplexes = { awaitCancellation() })
    h.alerts.fire("c1")
    assertEquals(emptyList(), h.posted)
    assertEquals(COMMUTE_FETCH_TIMEOUT_MS, testScheduler.currentTime)
  }

  private companion object {
    const val SLOW_LOAD_MS = 3_000L

    const val LOAD_MS = 2_000L
  }
}
