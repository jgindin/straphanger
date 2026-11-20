package com.straphanger.app.capture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.straphanger.app.data.capture.IdleCloseScheduler

/**
 * Wakes the app through [IdleCloseReceiver] when an open ride chain would go idle. The alarm is
 * inexact and allowed in Doze, so it needs no exact-alarm permission; a late close only closes the
 * ride late. A new request replaces the last, since both use the same [PendingIntent].
 */
class AlarmIdleCloseScheduler(context: Context) : IdleCloseScheduler {
  private val appContext = context.applicationContext
  private val alarms = appContext.getSystemService(AlarmManager::class.java)

  private val pendingIntent: PendingIntent by lazy {
    PendingIntent.getBroadcast(
      appContext,
      0,
      Intent(appContext, IdleCloseReceiver::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
  }

  override fun schedule(atEpochMs: Long) {
    alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pendingIntent)
  }

  override fun cancel() {
    alarms?.cancel(pendingIntent)
  }
}
