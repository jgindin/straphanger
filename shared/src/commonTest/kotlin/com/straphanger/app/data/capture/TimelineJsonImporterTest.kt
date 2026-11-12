package com.straphanger.app.data.capture

import com.straphanger.app.diagnostics.DiagnosticsLog
import com.straphanger.app.diagnostics.DiagnosticsSink
import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TimelineJsonImporterTest {

  @Test
  fun textThatIsNotJsonIsLoggedAndImportsNothing() {
    val previous = DiagnosticsLog.sink
    val warnings = mutableListOf<Throwable?>()
    val summary =
      try {
        DiagnosticsLog.sink = DiagnosticsSink { _, _, throwable -> warnings += throwable }
        TimelineJsonImporter(TestNetwork.engine()).resolveTimelineJson("not json {", emptyList())
      } finally {
        DiagnosticsLog.sink = previous
      }
    assertEquals(0, summary.rawSegmentsFound)
    assertEquals(0, summary.ridesImported)
    assertNotNull(warnings.single())
  }
}
