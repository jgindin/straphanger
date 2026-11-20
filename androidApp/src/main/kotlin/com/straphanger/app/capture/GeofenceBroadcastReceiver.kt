package com.straphanger.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.straphanger.app.data.capture.BackgroundWindowRefresh
import com.straphanger.app.data.capture.DynamicGeofenceWindowManager
import com.straphanger.app.data.capture.GeofenceEvent
import com.straphanger.app.data.capture.GeofenceEventBus

/**
 * Records station entries and exits (not the window anchor) in the pending-event file, then
 * forwards every transition to [GeofenceEventBus]. The file keeps entries that arrive while no view
 * model is running; the coordinator replays it when it starts and on each entry it receives. When
 * no coordinator is running, the process's [CaptureRuntimeHolder] runtime replays it here, which
 * records and notifies a finished ride, and leaving the window anchor registers a new window around
 * the triggering location.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return
    if (event.hasError()) {
      onError(context, event.errorCode)
      return
    }
    val pendingEvents = PassiveCaptureStorage.pendingEvents(context)
    val eventLog = PassiveCaptureStorage.eventLog(context)
    // Read before emitting: a running coordinator is a collector and handles the events itself.
    val appRunning = GeofenceEventBus.hasCollectors
    val transitions = transitionsOf(event)
    transitions.forEach {
      eventLog.record(it, System.currentTimeMillis())
      Log.i(TAG, "$it")
      if (isStation(it.geofenceId)) pendingEvents.append(it)
    }
    afterTransitions(context, transitions, appRunning)
    transitions.forEach { GeofenceEventBus.emit(it) }
  }

  /**
   * With no coordinator running (the app process was gone), turns the stored entries into rides
   * here, and moves the window when the anchor was left. Both share one [goAsync].
   */
  private fun afterTransitions(
    context: Context,
    transitions: List<GeofenceEvent>,
    appRunning: Boolean,
  ) {
    val anchorExit =
      transitions
        .firstOrNull { BackgroundWindowRefresh.shouldRefresh(it, appRunning) }
        ?.takeIf { BackgroundGeofenceRefresher.captureAllowed(context) }
    val refresh: (suspend () -> Unit)? = anchorExit?.let { exit ->
      { BackgroundGeofenceRefresher.refreshNow(context, "Anchor exit") { exit.lat to exit.lng } }
    }
    if (!appRunning && transitions.any { isStation(it.geofenceId) }) {
      CaptureRuntimeHolder.catchUpInBackground(this, context, "Geofence", alongside = refresh)
    } else if (anchorExit != null) {
      BackgroundGeofenceRefresher.refresh(this, context, "Anchor exit") {
        anchorExit.lat to anchorExit.lng
      }
    }
  }

  private fun onError(context: Context, errorCode: Int) {
    Log.w(TAG, "Geofence error: ${GeofenceStatusCodes.getStatusCodeString(errorCode)}")
    // Play services drops every geofence when device location is turned off.
    if (errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
      PassiveCaptureStorage.recordGeofencesDropped(context)
    }
  }

  /** One transition per triggering geofence; none without a triggering location. */
  private fun transitionsOf(event: GeofencingEvent): List<GeofenceEvent> {
    val location = event.triggeringLocation ?: return emptyList()
    val epochMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
    return event.triggeringGeofences.orEmpty().mapNotNull { geofence ->
      when (event.geofenceTransition) {
        Geofence.GEOFENCE_TRANSITION_ENTER ->
          GeofenceEvent.Entered(geofence.requestId, epochMs, location.latitude, location.longitude)
        Geofence.GEOFENCE_TRANSITION_EXIT ->
          GeofenceEvent.Exited(geofence.requestId, epochMs, location.latitude, location.longitude)
        else -> null
      }
    }
  }

  private fun isStation(geofenceId: String): Boolean =
    DynamicGeofenceWindowManager.complexIdForGeofence(geofenceId) != null

  private companion object {
    const val TAG = AndroidGeofenceRegistrar.TAG
  }
}
