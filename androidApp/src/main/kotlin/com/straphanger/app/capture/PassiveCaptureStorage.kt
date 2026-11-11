package com.straphanger.app.capture

import android.content.Context
import androidx.core.content.edit
import com.straphanger.app.data.capture.FilePendingGeofenceEventStore
import com.straphanger.app.data.capture.PendingGeofenceEventStore
import kotlinx.io.files.Path

/** Where passive capture keeps its state on Android. */
object PassiveCaptureStorage {
    private const val PREFS = "passive_capture"
    private const val KEY_TURNED_ON = "turned_on"
    private const val KEY_GEOFENCES_DROPPED = "geofences_dropped"

    fun pendingEvents(context: Context): PendingGeofenceEventStore =
        FilePendingGeofenceEventStore(Path(context.filesDir.path, "pending_geofence_events.json"))

    /** The user's choice; on until they turn it off. */
    fun isTurnedOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TURNED_ON, true)

    fun setTurnedOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_TURNED_ON, on) }
    }

    /** How many times Play services reported dropping every geofence; a new value means register again. */
    fun geofencesDroppedCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_GEOFENCES_DROPPED, 0)

    fun recordGeofencesDropped(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_GEOFENCES_DROPPED, prefs.getInt(KEY_GEOFENCES_DROPPED, 0) + 1) }
    }
}
