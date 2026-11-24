package com.straphanger.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback

/** ⟳ and "Tap to refresh": fetches the widget's shown commutes at once and redraws it. */
class RefreshWidgetAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters,
  ) {
    val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    CommuteWidgetsHolder.run(context, "Widget refresh") { it.tapRefresh(widgetId) }
  }
}
