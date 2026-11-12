package com.straphanger.app

import android.app.Application
import android.util.Log
import com.straphanger.app.diagnostics.DiagnosticsLog
import com.straphanger.app.diagnostics.DiagnosticsSink

/**
 * Sends shared code's diagnostics to logcat. Set here so it also covers processes started for the
 * geofence and boot receivers and the notification listener, not only the activity.
 */
class StraphangerApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    DiagnosticsLog.sink = LogcatDiagnosticsSink
  }
}

private object LogcatDiagnosticsSink : DiagnosticsSink {
  override fun warn(tag: String, message: String, throwable: Throwable?) {
    Log.w(tag, message, throwable)
  }
}
