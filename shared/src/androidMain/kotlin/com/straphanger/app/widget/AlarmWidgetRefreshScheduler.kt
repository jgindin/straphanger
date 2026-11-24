package com.straphanger.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.straphanger.app.data.widget.WidgetRefreshScheduler
import com.straphanger.app.diagnostics.DiagnosticsLog

/**
 * Wakes the app through [receiver] for each widget's two alarms, one [PendingIntent] per widget and
 * kind so a new alarm replaces only its own. The refresh alarm never wakes the device: it is exact
 * while the app may schedule exact alarms, else a 30 s window. The stale redraw is inexact but
 * wakes the device and runs in Doze, so a widget on an idle screen is not left with old times.
 *
 * @param receiver the app's refresh receiver; it reads the widget from [EXTRA_WIDGET_ID] and the
 *   kind of alarm from [EXTRA_WIDGET_ALARM].
 */
class AlarmWidgetRefreshScheduler(
  context: Context,
  private val receiver: Class<out BroadcastReceiver>,
) : WidgetRefreshScheduler {
  private val appContext = context.applicationContext
  private val alarms = appContext.getSystemService(AlarmManager::class.java)
  private val requestCodes = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** True when refresh alarms will be exact: always below Android 12, else while allowed. */
  fun canScheduleExact(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms?.canScheduleExactAlarms() == true

  override fun scheduleRefresh(widgetId: Int, atEpochMs: Long) {
    val alarms = alarms ?: return
    val operation = pendingIntent(widgetId, REFRESH)
    if (!canScheduleExact()) return alarms.setWindow(AlarmManager.RTC, atEpochMs, WINDOW_MS, operation)
    try {
      alarms.setExact(AlarmManager.RTC, atEpochMs, operation)
    } catch (e: SecurityException) {
      // The permission was revoked between the check and the call; a window still fires.
      DiagnosticsLog.warn(TAG, "Exact alarm refused for a widget; setting a window", e)
      alarms.setWindow(AlarmManager.RTC, atEpochMs, WINDOW_MS, operation)
    }
  }

  override fun scheduleStaleRedraw(widgetId: Int, atEpochMs: Long) {
    alarms?.setAndAllowWhileIdle(
      AlarmManager.RTC_WAKEUP,
      atEpochMs,
      pendingIntent(widgetId, STALE),
    )
  }

  override fun cancel(widgetId: Int) {
    alarms?.cancel(pendingIntent(widgetId, REFRESH))
    alarms?.cancel(pendingIntent(widgetId, STALE))
  }

  private fun pendingIntent(widgetId: Int, kind: String): PendingIntent {
    val intent =
      Intent(appContext, receiver)
        .putExtra(EXTRA_WIDGET_ID, widgetId)
        .putExtra(EXTRA_WIDGET_ALARM, kind)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent.setIdentifier("widget:$kind:$widgetId")
    return PendingIntent.getBroadcast(
      appContext,
      requestCode("$kind:$widgetId"),
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  /** A stable request code per alarm: the one it was given first, else the next free one. */
  @Synchronized
  private fun requestCode(key: String): Int {
    val existing = requestCodes.getInt(key, NO_CODE)
    if (existing != NO_CODE) return existing
    val next = requestCodes.all.values.filterIsInstance<Int>().maxOrNull()?.plus(1) ?: 0
    // Written at once: a process killed right after the alarm is set must not reuse the code.
    requestCodes.edit().putInt(key, next).commit()
    return next
  }

  companion object {
    /** The id of the widget whose alarm fired. */
    const val EXTRA_WIDGET_ID = "com.straphanger.app.WIDGET_ID"

    /** Which alarm fired: `"refresh"` or `"stale"`. */
    const val EXTRA_WIDGET_ALARM = "com.straphanger.app.WIDGET_ALARM"
    const val REFRESH = "refresh"
    const val STALE = "stale"
    private const val PREFS_NAME = "commute_widget_alarms"
    private const val WINDOW_MS = 30_000L
    private const val NO_CODE = -1
    private const val TAG = "StraphangerWidgets"
  }
}
