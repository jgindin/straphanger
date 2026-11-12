package com.straphanger.app.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.straphanger.app.data.capture.NotificationSessionSignal
import com.straphanger.app.data.capture.NotificationSessionTracker

/**
 * Follows Google Maps navigation notifications. Maps updates one ongoing notification many times
 * during a trip; [NotificationSessionTracker] keeps the latest text per notification key in a file
 * and ends the session when the notification is removed or another key replaces it. The view model
 * turns each ended session into one ride. This service never writes rides itself.
 */
class GoogleMapsTransitNotificationListener : NotificationListenerService() {

  private val tracker by lazy {
    NotificationSessionTracker(PassiveCaptureStorage.notificationSessions(applicationContext))
  }

  override fun onListenerConnected() {
    super.onListenerConnected()
    // A removal missed while disconnected ends the open session now.
    val active =
      try {
        activeNotifications
          .orEmpty()
          .filter { it.packageName == GOOGLE_MAPS_PACKAGE }
          .map { it.key }
          .toSet()
      } catch (e: SecurityException) {
        Log.w(TAG, "Active notifications unavailable", e)
        return
      }
    if (tracker.onActiveNotifications(active)) NotificationSessionSignal.sessionEnded()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    val notification = sbn?.takeIf { it.isMapsNavigation() } ?: return
    val combined = notification.navigationText()
    val postTime = notification.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
    if (
      combined.isNotBlank() &&
        tracker.onPosted(notification.key, postTime, "GMAPS_NOTIFICATION: $combined")
    ) {
      NotificationSessionSignal.sessionEnded()
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    val notification = sbn ?: return
    if (notification.packageName != GOOGLE_MAPS_PACKAGE) return
    if (tracker.onRemoved(notification.key, System.currentTimeMillis())) {
      NotificationSessionSignal.sessionEnded()
    }
  }

  /** Navigation is an ongoing notification; other Maps notifications are not trips. */
  private fun StatusBarNotification.isMapsNavigation(): Boolean =
    packageName == GOOGLE_MAPS_PACKAGE && isOngoing

  /** Title, text and sub-text joined with " | ", skipping blank parts; blank when all are. */
  private fun StatusBarNotification.navigationText(): String {
    val extras = notification?.extras ?: return ""
    return listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
      .map { extras.getCharSequence(it)?.toString().orEmpty() }
      .filter { it.isNotBlank() }
      .joinToString(" | ")
  }

  companion object {
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val TAG = "StraphangerMapsListener"
  }
}
