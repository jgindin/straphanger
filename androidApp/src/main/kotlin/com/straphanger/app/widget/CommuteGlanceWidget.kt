package com.straphanger.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import com.straphanger.app.commute.commuteTapActivityIntent

/**
 * The commute widget: its commutes' departures, drawn by [CommuteWidgetLayout] from what
 * [CommuteWidgetsHolder] says the widget shows, read again on every [GlanceWidgetRenderer] redraw.
 */
class CommuteGlanceWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Responsive(COMMUTE_WIDGET_SIZES)

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
    val widgets = CommuteWidgetsHolder.get(context)
    val loadedAt = GlanceWidgetRenderer.redraws.value
    val first = widgets.content(widgetId)
    val choose =
      Intent(context, CommuteWidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    provideContent {
      val redraw by GlanceWidgetRenderer.redraws.collectAsState()
      val content by
        produceState(first, redraw) { if (redraw != loadedAt) value = widgets.content(widgetId) }
      CommuteWidgetLayout(
        content,
        refresh = actionRunCallback<RefreshWidgetAction>(),
        open = { actionStartActivity(commuteTapActivityIntent(context, it)) },
        choose = actionStartActivity(choose),
      )
    }
  }
}
