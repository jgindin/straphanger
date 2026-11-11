package com.straphanger.app.capture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Notification access for [GoogleMapsTransitNotificationListener]. */
object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, GoogleMapsTransitNotificationListener::class.java)
            )
        }
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    /** Opens the system list of apps with notification access, where the user grants or revokes it. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}
