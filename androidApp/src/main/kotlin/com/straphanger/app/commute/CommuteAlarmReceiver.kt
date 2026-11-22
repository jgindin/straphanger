package com.straphanger.app.commute

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.straphanger.app.notify.AlarmCommuteScheduler

/** A commute's alarm: sets the next one and posts the commute's live departures. */
class CommuteAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val commuteId = intent.getStringExtra(AlarmCommuteScheduler.EXTRA_COMMUTE_ID)
    if (commuteId == null) {
      Log.w("StraphangerCommutes", "Commute alarm without a commute id")
      return
    }
    CommuteAlertsHolder.runInBackground(this, context, "Commute alarm") { it.fire(commuteId) }
  }
}
