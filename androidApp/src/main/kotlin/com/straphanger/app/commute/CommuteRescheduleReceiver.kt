package com.straphanger.app.commute

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Sets every commute's alarm again when the platform may have dropped or misplaced them: after a
 * reboot or an app update, when the clock or time zone changes, and when the exact-alarm permission
 * changes (so alarms become exact once it is granted).
 */
class CommuteRescheduleReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action !in ACTIONS) return
    CommuteAlertsHolder.runInBackground(this, context, "Commute reschedule (${intent.action})") {
      it.rescheduleAll()
    }
  }

  private companion object {
    val ACTIONS =
      setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, from Android 12.
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
      )
  }
}
