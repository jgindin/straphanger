package com.straphanger.app.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.straphanger.app.data.capture.ActiveGeofenceSpec
import com.straphanger.app.data.capture.GeofenceRegistrar
import com.straphanger.app.data.capture.GeofenceRegistrationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Registers geofences with Google Play services; transitions go to [GeofenceBroadcastReceiver]. */
class AndroidGeofenceRegistrar(context: Context) : GeofenceRegistrar {
    private val appContext = context.applicationContext
    private val client = LocationServices.getGeofencingClient(appContext)

    // Geofencing fills in the intent's extras, so the PendingIntent must be mutable.
    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, GeofenceBroadcastReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission") // A missing permission throws SecurityException, handled below.
    override suspend fun replaceAll(specs: List<ActiveGeofenceSpec>) {
        val geofences = specs.map { spec ->
            Geofence.Builder()
                .setRequestId(spec.geofenceId)
                .setCircularRegion(spec.lat, spec.lng, spec.radiusMeters.toFloat())
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }
        // Report the regions the device is already inside, and an exit from the anchor when the
        // window was centred somewhere else, so the window moves to the real location.
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(geofences)
            .build()
        translateFailures {
            client.removeGeofences(pendingIntent).await()
            client.addGeofences(request, pendingIntent).await()
        }
        Log.i(TAG, "Registered ${geofences.size} geofences: ${specs.joinToString { it.geofenceId }}")
    }

    override suspend fun clear() {
        translateFailures { client.removeGeofences(pendingIntent).await() }
        Log.i(TAG, "Removed all geofences")
    }

    private suspend fun translateFailures(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            Log.w(TAG, "Geofence registration refused", e)
            throw GeofenceRegistrationException("Location permission is needed", e)
        } catch (e: ApiException) {
            Log.w(TAG, "Geofence registration failed: ${GeofenceStatusCodes.getStatusCodeString(e.statusCode)}", e)
            throw GeofenceRegistrationException(messageFor(e.statusCode), e)
        }
    }

    private fun messageFor(statusCode: Int): String = when (statusCode) {
        GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
            "Location permission \"Allow all the time\" is needed"
        GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
            "Geofencing is not available. Check that location is turned on."
        GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> "Too many geofences are registered"
        GeofenceStatusCodes.GEOFENCE_REQUEST_TOO_FREQUENT -> "Geofences were updated too often; try again shortly"
        else -> "Geofences could not be registered (${GeofenceStatusCodes.getStatusCodeString(statusCode)})"
    }

    companion object {
        const val TAG = "StraphangerGeofence"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel(CancellationException("Task was cancelled")) }
}
