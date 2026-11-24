package com.straphanger.app.data.widget

/** Redraws one placed widget from [CommuteWidgets.content]. */
fun interface WidgetRenderer {
  suspend fun update(widgetId: Int)

  companion object {
    /** For platforms with no widget. */
    val None = WidgetRenderer {}
  }
}
