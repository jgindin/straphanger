package com.straphanger.app.data.widget

/**
 * A widget's two alarms. Each widget has at most one of each, and a new one replaces the last: the
 * refresh alarm runs [CommuteWidgets.onRefreshAlarm], and the stale redraw runs
 * [CommuteWidgets.onStaleAlarm] once shown times have grown old.
 */
interface WidgetRefreshScheduler {
  fun scheduleRefresh(widgetId: Int, atEpochMs: Long)

  fun scheduleStaleRedraw(widgetId: Int, atEpochMs: Long)

  /** Cancels both of the widget's alarms. */
  fun cancel(widgetId: Int)

  companion object {
    /** For platforms with no widget. */
    val None: WidgetRefreshScheduler =
      object : WidgetRefreshScheduler {
        override fun scheduleRefresh(widgetId: Int, atEpochMs: Long) = Unit

        override fun scheduleStaleRedraw(widgetId: Int, atEpochMs: Long) = Unit

        override fun cancel(widgetId: Int) = Unit
      }
  }
}
