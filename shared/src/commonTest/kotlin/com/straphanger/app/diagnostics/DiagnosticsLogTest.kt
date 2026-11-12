package com.straphanger.app.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DiagnosticsLogTest {

  @Test
  fun theDefaultSinkPrints() {
    assertSame(PrintlnDiagnosticsSink, DiagnosticsLog.sink)
  }

  @Test
  fun aWarningWithoutAThrowableIsTagAndMessage() {
    assertEquals("W/Store: file set aside", formatDiagnostic("Store", "file set aside", null))
  }

  @Test
  fun aWarningWithAThrowableNamesIt() {
    assertEquals(
      "W/Store: unreadable: Bad: bad",
      formatDiagnostic("Store", "unreadable", NamedFailure("bad")),
    )
  }

  @Test
  fun warningsGoToTheCurrentSink() {
    val previous = DiagnosticsLog.sink
    val received = mutableListOf<Triple<String, String, Throwable?>>()
    val failure = IllegalStateException("bad")
    try {
      DiagnosticsLog.sink = DiagnosticsSink { tag, message, t ->
        received += Triple(tag, message, t)
      }
      DiagnosticsLog.warn("Store", "unreadable", failure)
    } finally {
      DiagnosticsLog.sink = previous
    }
    assertEquals(listOf(Triple("Store", "unreadable", failure as Throwable?)), received)
  }
}

private class NamedFailure(message: String) : Exception(message) {
  override fun toString(): String = "Bad: $message"
}
