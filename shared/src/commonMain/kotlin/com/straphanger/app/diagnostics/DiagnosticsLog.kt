package com.straphanger.app.diagnostics

import kotlin.concurrent.Volatile

/** Receives warnings about failures that were handled but should not go unnoticed. */
fun interface DiagnosticsSink {
  fun warn(tag: String, message: String, throwable: Throwable?)
}

/** Writes each warning to standard output with [formatDiagnostic]. */
object PrintlnDiagnosticsSink : DiagnosticsSink {
  override fun warn(tag: String, message: String, throwable: Throwable?) {
    println(formatDiagnostic(tag, message, throwable))
  }
}

/**
 * Where shared code reports handled failures. Shared code has no logging dependency, so the
 * platform sets [sink] at process start (Android sends warnings to `android.util.Log`); until then
 * warnings go to [PrintlnDiagnosticsSink].
 */
object DiagnosticsLog {
  @Volatile var sink: DiagnosticsSink = PrintlnDiagnosticsSink

  fun warn(tag: String, message: String, throwable: Throwable? = null) {
    sink.warn(tag, message, throwable)
  }
}

/** `W/<tag>: <message>`, followed by the throwable's description when there is one. */
fun formatDiagnostic(tag: String, message: String, throwable: Throwable?): String =
  if (throwable == null) "W/$tag: $message" else "W/$tag: $message: $throwable"
