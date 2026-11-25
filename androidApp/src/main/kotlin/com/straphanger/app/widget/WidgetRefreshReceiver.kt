package com.straphanger.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.straphanger.app.widget.AlarmWidgetRefreshScheduler.Companion.EXTRA_WIDGET_ALARM
import com.straphanger.app.widget.AlarmWidgetRefreshScheduler.Companion.EXTRA_WIDGET_ID
import com.straphanger.app.widget.AlarmWidgetRefreshScheduler.Companion.STALE

/** A widget's alarm: refreshes its departures, or redraws it once its times have grown old. */
class WidgetRefreshReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, NO_WIDGET)
    if (widgetId == NO_WIDGET) return
    val stale = intent.getStringExtra(EXTRA_WIDGET_ALARM) == STALE
    CommuteWidgetsHolder.runInBackground(this, context, "Widget $widgetId alarm (stale=$stale)") {
      if (stale) it.onStaleAlarm(widgetId) else it.onRefreshAlarm(widgetId)
    }
  }

  private companion object {
    const val NO_WIDGET = -1
  }
}
