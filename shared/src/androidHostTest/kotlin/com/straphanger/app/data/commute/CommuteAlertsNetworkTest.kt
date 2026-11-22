package com.straphanger.app.data.commute

import com.straphanger.app.diagnostics.DiagnosticsLog
import com.straphanger.app.diagnostics.DiagnosticsSink
import kotlin.test.Test
import kotlin.test.assertEquals
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

  private companion object {
    const val LOAD_MS = 2_000L
  }
}
