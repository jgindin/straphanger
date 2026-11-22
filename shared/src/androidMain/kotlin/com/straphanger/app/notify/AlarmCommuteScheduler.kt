package com.straphanger.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.straphanger.app.data.commute.CommuteAlarmScheduler
import com.straphanger.app.diagnostics.DiagnosticsLog

/**
 * Wakes the app through [receiver] at each commute's time, with one [PendingIntent] per commute so
 * a new alarm for one commute replaces only its own. Alarms are exact and allowed in Doze while
 * the app may schedule exact alarms, and inexact otherwise, so they may then come a little late.
 *
 * @param receiver the app's alarm receiver; it reads the commute from [EXTRA_COMMUTE_ID].
 */
class AlarmCommuteScheduler(
  context: Context,
  private val receiver: Class<out BroadcastReceiver>,
) : CommuteAlarmScheduler {
  private val appContext = context.applicationContext
  private val alarms = appContext.getSystemService(AlarmManager::class.java)
  private val requestCodes = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** True when alarms will be exact: always below Android 12, else while the user allows it. */
  fun canScheduleExact(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms?.canScheduleExactAlarms() == true

  override fun schedule(commuteId: String, atEpochMs: Long) {
    val alarms = alarms ?: return
    val operation = pendingIntent(commuteId)
    try {
      if (canScheduleExact()) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, operation)
      } else {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, operation)
      }
    } catch (e: SecurityException) {
      // The permission was revoked between the check and the call; an inexact alarm still fires.
      DiagnosticsLog.warn(TAG, "Exact alarm refused for a commute; setting an inexact one", e)
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, operation)
    }
  }

  override fun cancel(commuteId: String) {
    alarms?.cancel(pendingIntent(commuteId))
  }

  private fun pendingIntent(commuteId: String): PendingIntent {
    val intent = Intent(appContext, receiver).putExtra(EXTRA_COMMUTE_ID, commuteId)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent.setIdentifier(commuteId)
    return PendingIntent.getBroadcast(
      appContext,
      requestCode(commuteId),
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  /** A stable request code per commute: the one it was given first, else the next free one. */
  @Synchronized
  private fun requestCode(commuteId: String): Int {
    val existing = requestCodes.getInt(commuteId, NO_CODE)
    if (existing != NO_CODE) return existing
    val next = requestCodes.all.values.filterIsInstance<Int>().maxOrNull()?.plus(1) ?: 0
    // Written at once: a process killed right after the alarm is set must not reuse the code.
    requestCodes.edit().putInt(commuteId, next).commit()
    return next
  }

  companion object {
    /** The id of the commute whose alarm fired. */
    const val EXTRA_COMMUTE_ID = "com.straphanger.app.COMMUTE_ID"
    private const val PREFS_NAME = "commute_alarms"
    private const val NO_CODE = -1
    private const val TAG = "StraphangerCommutes"
  }
}
