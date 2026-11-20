package com.straphanger.app.notify

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.straphanger.app.data.capture.RideNotificationText
import com.straphanger.app.data.capture.RideNotifier
import com.straphanger.app.diagnostics.DiagnosticsLog

/**
 * Posts one notification per recorded ride on the [CHANNEL_ID] channel, which it creates on the
 * first post. Posts nothing while the user's setting is off ([turnedOn]) or the app's
 * notifications are blocked. Never throws: the runtime calls it inside the save path.
 *
 * @param smallIcon the app's monochrome status bar icon; the app module owns its resources.
 * @param tapIntent opens the app on the given ride.
 */
class AndroidRideNotifier(
  context: Context,
  @param:DrawableRes private val smallIcon: Int,
  private val turnedOn: () -> Boolean,
  private val tapIntent: (rideId: String) -> PendingIntent,
) : RideNotifier {
  private val appContext = context.applicationContext
  private val manager = NotificationManagerCompat.from(appContext)

  // RuntimeException as well as SecurityException: a failure to post must not fail the save that
  // called this, and the platform's notification service can throw others (e.g. a bad icon).
  @Suppress("TooGenericExceptionCaught")
  override fun rideRecorded(rideId: String, text: RideNotificationText) {
    try {
      if (turnedOn() && manager.areNotificationsEnabled()) post(rideId, text)
    } catch (e: SecurityException) {
      DiagnosticsLog.warn(TAG, "Notification for ride $rideId refused", e)
    } catch (e: RuntimeException) {
      DiagnosticsLog.warn(TAG, "Notification for ride $rideId not posted", e)
    }
  }

  @SuppressLint("MissingPermission") // areNotificationsEnabled() was checked; a refusal throws.
  private fun post(rideId: String, text: RideNotificationText) {
    manager.createNotificationChannel(
      NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
        .setName(CHANNEL_NAME)
        .build(),
    )
    val notification =
      NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(smallIcon)
        .setContentTitle(text.title)
        .setContentText(text.text)
        .setContentIntent(tapIntent(rideId))
        .setAutoCancel(true)
        .build()
    manager.notify(rideId, rideId.hashCode(), notification)
  }

  companion object {
    const val CHANNEL_ID = "rides_recorded"
    const val CHANNEL_NAME = "Rides recorded"
    private const val TAG = "StraphangerNotify"
  }
}
