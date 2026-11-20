package com.straphanger.app.capture

import android.content.Context
import androidx.core.content.edit
import com.straphanger.app.data.capture.FileGeofenceEventLog
import com.straphanger.app.data.capture.FileNotificationSessionStore
import com.straphanger.app.data.capture.FilePendingGeofenceEventStore
import com.straphanger.app.data.capture.GeofenceEventLog
import com.straphanger.app.data.capture.NotificationSessionStore
import com.straphanger.app.data.capture.PendingGeofenceEventStore
import kotlinx.io.files.Path

/** Where passive capture keeps its state on Android. */
object PassiveCaptureStorage {
  private const val PREFS = "passive_capture"
  private const val KEY_TURNED_ON = "turned_on"
  private const val KEY_GEOFENCES_DROPPED = "geofences_dropped"
  private const val KEY_RIDE_NOTIFICATIONS = "ride_notifications_turned_on"

  fun pendingEvents(context: Context): PendingGeofenceEventStore =
    FilePendingGeofenceEventStore(Path(context.filesDir.path, "pending_geofence_events.json"))

  /** Every geofence transition received, for diagnosing wrongly recorded rides. */
  fun eventLog(context: Context): GeofenceEventLog =
    FileGeofenceEventLog(Path(context.filesDir.path, "geofence_event_log.json"))

  /** Google Maps navigation sessions recorded by the notification listener. */
  fun notificationSessions(context: Context): NotificationSessionStore =
    FileNotificationSessionStore(Path(context.filesDir.path, "notification_sessions.json"))

  /** The user's choice; off until they turn it on through the disclosure. */
  fun isTurnedOn(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TURNED_ON, false)

  fun setTurnedOn(context: Context, on: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_TURNED_ON, on) }
  }

  /** "Notify me when a ride is recorded"; off until the user turns it on. */
  fun rideNotificationsTurnedOn(context: Context): Boolean =
    context
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getBoolean(KEY_RIDE_NOTIFICATIONS, false)

  fun setRideNotificationsTurnedOn(context: Context, on: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
      putBoolean(KEY_RIDE_NOTIFICATIONS, on)
    }
  }

  private const val KEY_ANCHOR_LAT = "window_anchor_lat"
  private const val KEY_ANCHOR_LNG = "window_anchor_lng"

  /**
   * Centre of the last registered geofence window, or null; used after a reboot with no last known
   * location.
   */
  fun lastWindowAnchor(context: Context): Pair<Double, Double>? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!prefs.contains(KEY_ANCHOR_LAT) || !prefs.contains(KEY_ANCHOR_LNG)) return null
    return Double.fromBits(prefs.getLong(KEY_ANCHOR_LAT, 0L)) to
      Double.fromBits(prefs.getLong(KEY_ANCHOR_LNG, 0L))
  }

  fun recordWindowAnchor(context: Context, lat: Double, lng: Double) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
      putLong(KEY_ANCHOR_LAT, lat.toRawBits())
      putLong(KEY_ANCHOR_LNG, lng.toRawBits())
    }
  }

  /**
   * How many times Play services reported dropping every geofence; a new value means register
   * again.
   */
  fun geofencesDroppedCount(context: Context): Int =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_GEOFENCES_DROPPED, 0)

  fun recordGeofencesDropped(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit { putInt(KEY_GEOFENCES_DROPPED, prefs.getInt(KEY_GEOFENCES_DROPPED, 0) + 1) }
  }
}
