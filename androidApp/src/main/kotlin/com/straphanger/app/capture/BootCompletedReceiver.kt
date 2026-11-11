package com.straphanger.app.capture

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Play services drops every geofence on reboot. When passive capture is on and location access is
 * granted, registers the window again around the last known location, or the centre of the last
 * registered window when the platform has no location yet (usual right after boot). Skips and logs
 * when there is neither.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BackgroundGeofenceRefresher.captureAllowed(context)) {
            Log.i(AndroidGeofenceRegistrar.TAG, "Boot: passive capture off or location not granted; nothing registered")
            return
        }
        BackgroundGeofenceRefresher.refresh(this, context, "Boot") {
            lastKnownLocation(context)
                ?: PassiveCaptureStorage.lastWindowAnchor(context)?.also {
                    Log.i(AndroidGeofenceRegistrar.TAG, "Boot: no last known location; using the last window centre")
                }
        }
    }

    @SuppressLint("MissingPermission") // captureAllowed checked fine location; a refusal throws and is logged.
    private suspend fun lastKnownLocation(context: Context): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnCompleteListener { task ->
                val location = if (task.isSuccessful) task.result else null
                cont.resume(location?.let { it.latitude to it.longitude })
            }
        }
}
