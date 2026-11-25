package com.straphanger.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.straphanger.app.data.widget.WidgetRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Redraws a placed widget through [widget]. A widget id the system no longer knows, as after a
 * removal or a restore, is passed to [onGone] instead, so it can be forgotten; it never throws.
 */
class GlanceWidgetRenderer(
  private val context: Context,
  private val widget: GlanceAppWidget,
  private val onGone: (Int) -> Unit,
) : WidgetRenderer {
  override suspend fun update(widgetId: Int) {
    // getGlanceIdBy throws for an id without provider info.
    if (AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) == null) {
      Log.w(TAG, "Widget $widgetId is no longer placed")
      onGone(widgetId)
      return
    }
    mutableRedraws.update { it + 1 }
    widget.update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId))
  }

  companion object {
    private const val TAG = "StraphangerWidgets"

    private val mutableRedraws = MutableStateFlow(0L)

    /**
     * Counts redraws. Glance's update only recomposes a widget whose session is still running, so
     * that session reads its content again whenever this changes.
     */
    val redraws: StateFlow<Long> = mutableRedraws
  }
}
