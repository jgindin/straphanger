package com.straphanger.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.straphanger.app.data.capture.GeofenceEvent
import com.straphanger.app.data.capture.GeofenceEventBus

/** Forwards geofence transitions from Google Play services to [GeofenceEventBus]. */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofence error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            return
        }
        val location = event.triggeringLocation ?: return
        val epochMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        for (geofence in event.triggeringGeofences.orEmpty()) {
            val transition = when (event.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_ENTER ->
                    GeofenceEvent.Entered(geofence.requestId, epochMs, location.latitude, location.longitude)
                Geofence.GEOFENCE_TRANSITION_EXIT ->
                    GeofenceEvent.Exited(geofence.requestId, epochMs, location.latitude, location.longitude)
                else -> null
            } ?: continue
            Log.i(TAG, "$transition")
            GeofenceEventBus.emit(transition)
        }
    }

    private companion object {
        const val TAG = AndroidGeofenceRegistrar.TAG
    }
}
