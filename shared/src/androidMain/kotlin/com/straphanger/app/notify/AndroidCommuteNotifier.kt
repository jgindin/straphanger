package com.straphanger.app.notify

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.straphanger.app.data.commute.CommuteAlertText
import com.straphanger.app.data.commute.CommuteNotifier
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.diagnostics.DiagnosticsLog

/**
 * Posts a commute's departures on the [CHANNEL_ID] channel, which it creates on each post, tagged
 * with the commute's id so a later alarm for the same commute replaces it. Posts nothing while the
 * app's notifications are blocked. Never throws: it runs inside an alarm's broadcast.
 *
 * @param smallIcon the app's monochrome status bar icon; the app module owns its resources.
 * @param tapIntent opens the Status tab on the commute's station, line and direction.
 */
class AndroidCommuteNotifier(
  context: Context,
  @param:DrawableRes private val smallIcon: Int,
  private val tapIntent: (tap: CommuteTap, commuteId: String) -> PendingIntent,
) : CommuteNotifier {
  private val appContext = context.applicationContext
  private val manager = NotificationManagerCompat.from(appContext)

  // RuntimeException as well as SecurityException: the platform's notification service can throw
  // others (e.g. a bad icon), and a failure must not crash the alarm's receiver.
  @Suppress("TooGenericExceptionCaught")
  override fun post(commuteId: String, text: CommuteAlertText, tap: CommuteTap) {
    try {
      if (manager.areNotificationsEnabled()) notify(commuteId, text, tap)
    } catch (e: SecurityException) {
      DiagnosticsLog.warn(TAG, "Notification for a commute refused", e)
    } catch (e: RuntimeException) {
      DiagnosticsLog.warn(TAG, "Notification for a commute not posted", e)
    }
  }

  @SuppressLint("MissingPermission") // areNotificationsEnabled() was checked; a refusal throws.
  private fun notify(commuteId: String, text: CommuteAlertText, tap: CommuteTap) {
    manager.createNotificationChannel(
      NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
        .setName(CHANNEL_NAME)
        .build(),
    )
    val open = tapIntent(tap, commuteId)
    val builder =
      NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(smallIcon)
        .setContentTitle(text.title)
        .setContentText(text.text)
        .setContentIntent(open)
        .addAction(0, ACTION_LABEL, open)
        .setAutoCancel(true)
    text.alertLine?.let {
      builder.setStyle(NotificationCompat.BigTextStyle().bigText(text.text + "\n" + it))
    }
    manager.notify(commuteId, NOTIFICATION_ID, builder.build())
  }

  companion object {
    const val CHANNEL_ID = "commute_alerts"
    const val CHANNEL_NAME = "Commute alerts"
    const val ACTION_LABEL = "See trains"

    /** Every commute posts with this id; its tag, the commute's id, keeps them apart. */
    const val NOTIFICATION_ID = 1
    private const val TAG = "StraphangerNotify"
  }
}
