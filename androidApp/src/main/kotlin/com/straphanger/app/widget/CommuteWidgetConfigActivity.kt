package com.straphanger.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.lifecycleScope
import com.straphanger.app.MainActivity
import com.straphanger.app.capture.CaptureRuntimeHolder
import com.straphanger.app.commute.CommuteAlertsHolder
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.commute.stationComplexes
import com.straphanger.app.ui.widget.WidgetCommutePicker
import kotlinx.coroutines.launch

/**
 * Chooses the commutes a widget shows, when it is placed, reconfigured from the launcher, or its
 * "Tap to choose commutes" is tapped. Leaving without Done cancels placing the widget.
 */
class CommuteWidgetConfigActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // The app is always dark, so the system bar icons are always light.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    setResult(RESULT_CANCELED)
    val widgetId =
      intent?.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID,
      ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    if (!isCommuteWidget(widgetId)) {
      finish()
      return
    }
    val commutes = CommuteAlertsHolder.store(this).read()
    val selected = CommuteWidgetsHolder.get(this).commuteIds(widgetId).toSet()
    val runtime = CaptureRuntimeHolder.get(applicationContext)
    setContent {
      // Waits for the station names, so no commute shows as "Station no longer listed" meanwhile.
      val complexes by
        produceState<List<StationComplex>?>(null) {
          value = runtime.network().map(::stationComplexes).getOrNull().orEmpty()
        }
      val loaded = complexes
      if (loaded != null) {
        WidgetCommutePicker(
          commutes = commutes,
          complexName = { commute ->
            loaded.firstOrNull { it.complexId == commute.complexId }?.name
          },
          initiallySelected = selected,
          onDone = { done(widgetId, it) },
          onOpenApp = ::openApp,
        )
      }
    }
  }

  /** Only this app's commute widgets; another app could start this screen with any id. */
  private fun isCommuteWidget(widgetId: Int): Boolean =
    widgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
      AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider ==
        ComponentName(this, CommuteWidgetReceiver::class.java)

  /** Saves [commuteIds] for the widget and redraws it, then places it. */
  private fun done(widgetId: Int, commuteIds: List<String>) {
    lifecycleScope.launch {
      CommuteWidgetsHolder.launch(this@CommuteWidgetConfigActivity, "Configuring a widget") {
          it.configure(widgetId, commuteIds)
        }
        .join()
      setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
      finish()
    }
  }

  private fun openApp() {
    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    finish()
  }
}
