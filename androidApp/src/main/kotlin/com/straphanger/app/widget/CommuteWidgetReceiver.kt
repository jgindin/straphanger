package com.straphanger.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** The commute widget's provider; forgets a widget, and its alarms, when it is removed. */
class CommuteWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = CommuteGlanceWidget()

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    // A placed or launcher-restored widget gets its alarms; Glance draws it as it always does.
    super.onUpdate(context, appWidgetManager, appWidgetIds)
    CommuteWidgetsHolder.launch(context, "Scheduling widgets") { it.rescheduleAll() }
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) {
    // Glance's own onDeleted holds the broadcast with goAsync, which can be called only once, so
    // this runs on the runtime's scope instead; forgetting the widgets is quick and local.
    super.onDeleted(context, appWidgetIds)
    val ids = appWidgetIds.toList()
    CommuteWidgetsHolder.launch(context, "Removing widgets") { it.removed(ids) }
  }
}
