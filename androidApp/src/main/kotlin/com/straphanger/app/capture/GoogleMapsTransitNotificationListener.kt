package com.straphanger.app.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.straphanger.app.SharedRouteInbox

class GoogleMapsTransitNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName != GOOGLE_MAPS_PACKAGE) return
        val extras = notification.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val combined = listOf(title, text, subText).filter { it.isNotBlank() }.joinToString(" | ")
        if (combined.isNotBlank()) {
            SharedRouteInbox.enqueueSharedRouteText("GMAPS_NOTIFICATION: $combined")
        }
    }

    companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
