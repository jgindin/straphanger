package com.straphanger.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.straphanger.app.data.widget.WidgetRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Redraws a placed widget through Glance, which reads its content from [CommuteWidgetsHolder]. */
class GlanceWidgetRenderer(private val context: Context) : WidgetRenderer {
  override suspend fun update(widgetId: Int) {
    mutableRedraws.update { it + 1 }
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
    CommuteGlanceWidget().update(context, glanceId)
  }

  companion object {
    private val mutableRedraws = MutableStateFlow(0L)

    /**
     * Counts redraws. Glance's update only recomposes a widget whose session is still running, so
     * that session reads its content again whenever this changes.
     */
    val redraws: StateFlow<Long> = mutableRedraws
  }
}
