package com.straphanger.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import com.straphanger.app.data.capture.BackgroundWindowRefresh
import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.permissions.CapturePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registers the geofence window from a broadcast receiver, when no view model is running. Uses
 * [BroadcastReceiver.goAsync] with a bounded coroutine; nothing here touches the rides.
 */
object BackgroundGeofenceRefresher {
    private const val TAG = AndroidGeofenceRegistrar.TAG
    private const val TIMEOUT_MS = 20_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True when the user turned passive capture on and fine and background location are granted. */
    fun captureAllowed(context: Context): Boolean =
        PassiveCaptureStorage.isTurnedOn(context) &&
            CapturePermissions.fineLocationGranted(context) &&
            CapturePermissions.backgroundLocationGranted(context)

    /** Registers the window around a location; [location] runs first and returns null to skip. */
    fun refresh(
        receiver: BroadcastReceiver,
        context: Context,
        reason: String,
        location: suspend () -> Pair<Double, Double>?
    ) {
        val appContext = context.applicationContext
        val pending = receiver.goAsync()
        scope.launch {
            try {
                val done = withTimeoutOrNull(TIMEOUT_MS) {
                    val point = location()
                    if (point == null) {
                        Log.i(TAG, "$reason: no location, geofences not registered")
                        return@withTimeoutOrNull
                    }
                    val network = NycSubwayNetworkCatalog.loadBundled()
                    val specs = BackgroundWindowRefresh.windowAt(network, point.first, point.second)
                    AndroidGeofenceRegistrar(appContext).replaceAll(specs)
                    Log.i(TAG, "$reason: registered ${specs.size} geofences around ${point.first},${point.second}")
                }
                if (done == null) Log.w(TAG, "$reason: timed out registering geofences")
            } catch (e: Exception) {
                Log.w(TAG, "$reason: geofences not registered", e)
            } finally {
                pending.finish()
            }
        }
    }
}
