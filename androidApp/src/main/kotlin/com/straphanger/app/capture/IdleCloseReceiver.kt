package com.straphanger.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The [AlarmIdleCloseScheduler] alarm: closes a ride chain that went idle while no app was running,
 * and posts its notification.
 */
class IdleCloseReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    CaptureRuntimeHolder.catchUpInBackground(this, context, "Idle close")
  }
}
